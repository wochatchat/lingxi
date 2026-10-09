package com.lingxi.data

import com.lingxi.data.memory.MemoryPrompts
import com.lingxi.data.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 对话回合状态（级联管线：ASR → LLM 流式 → 句切分 → TTS 队列播报） */
sealed interface ConvState {
    data object Idle : ConvState
    data object Transcribing : ConvState
    data class Thinking(val partial: String) : ConvState
    data class Speaking(val partial: String) : ConvState
    data class Failed(val user: String, val message: String) : ConvState
}

/** 一轮已完成的对话（UI 历史展示） */
data class ConvTurn(val user: String, val reply: String)

/**
 * 对话引擎（PRD §12 级联模式骨架）：
 * 输入文本（ASR 在 VM 层完成）→ LLM 流式 → 句切分 → TTS 队列播报。
 * barge-in 打断、G 级门控、常听管线在 R3/R4 接入；本类保持纯级联语义。
 */
@javax.inject.Singleton
class ConversationEngine @javax.inject.Inject constructor(
    private val llm: LlmStream,
    private val tts: TtsEngine,
    private val memory: com.lingxi.data.memory.MemoryStore,
) {
    private val _state = MutableStateFlow<ConvState>(ConvState.Idle)
    val state: StateFlow<ConvState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ConvTurn>>(emptyList())
    val history: StateFlow<List<ConvTurn>> = _history.asStateFlow()

    private val messages = ArrayDeque<ChatMessage>()

    /** 正在跑的回合协程：cancel 时连根取消，防止旧回合在打断后继续落账/改状态 */
    @Volatile private var currentJob: Job? = null

    /** 会话滑窗是否已从 Room 恢复（进程生命周期内只恢复一次） */
    @Volatile private var seeded = false

    /**
     * 跑一轮对话。挂起直到 LLM 流结束且 TTS 播完。
     * 协程取消（barge-in 前身）时停播并上抛取消。
     */
    suspend fun runTurn(
        userText: String,
        apiKey: String,
        baseUrl: String,
        model: String,
    ) {
        val user = userText.trim()
        if (user.isEmpty()) return
        currentJob = currentCoroutineContext()[Job]
        val reply = StringBuilder()
        try {
            seedFromMemory()
            val systemPrompt = buildMemoryPrompt()
            val request = buildList {
                add(ChatMessage("system", systemPrompt))
                addAll(messages.toList())
                add(ChatMessage("user", user))
            }
            _state.value = ConvState.Thinking("")
            // 首句预读：12 字内遇停顿即切出，TTS 队列天然预读后续句（PRD「提前 2 句预读」基座）
            val splitter = SentenceSplitter(eagerFirstSplitChars = EAGER_FIRST_CHARS)
            var anyEnqueued = false
            llm.streamChat(apiKey, baseUrl, model, request).collect { ev ->
                when (ev) {
                    is LlmEvent.Delta -> {
                        reply.append(ev.text)
                        for (sentence in splitter.feed(ev.text)) {
                            tts.enqueue(stripMarkdownForSpeech(sentence))
                            anyEnqueued = true
                        }
                        _state.value = ConvState.Thinking(reply.toString())
                    }
                    is LlmEvent.Completed -> Unit
                    is LlmEvent.Failed -> throw RuntimeException(ev.message)
                }
            }
            for (sentence in splitter.flush()) {
                tts.enqueue(stripMarkdownForSpeech(sentence))
                anyEnqueued = true
            }
            _state.value = ConvState.Speaking(reply.toString())
            if (anyEnqueued) tts.awaitIdle()
            val replyText = reply.toString().trim()
            messages.addLast(ChatMessage("user", user))
            messages.addLast(ChatMessage("assistant", replyText))
            while (messages.size > MAX_HISTORY * 2) messages.removeFirst()
            _history.value = _history.value + ConvTurn(user, replyText)
            // F7 三层记忆：落库 + 异步画像提取 / 日滚摘要（失败不影响对话）
            runCatching { memory.onTurnCompleted(user, replyText) }
            _state.value = ConvState.Idle
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } catch (e: Exception) {
            tts.stop()
            _state.value = ConvState.Failed(user, e.message ?: "对话失败")
        }
    }

    /** 语音输入完整一轮：ASR → runTurn（ASR 失败不进对话） */
    suspend fun runVoiceTurn(
        audio: ByteArray,
        sampleRate: Int,
        asr: AsrEngine,
        apiKey: String,
        baseUrl: String,
        model: String,
    ) {
        _state.value = ConvState.Transcribing
        val text = asr.transcribe(audio, sampleRate).getOrNull()
        if (text.isNullOrBlank()) {
            _state.value = ConvState.Failed("", "未能识别语音，请再试一次")
            return
        }
        runTurn(text, apiKey, baseUrl, model)
    }

    /** barge-in/手动打断：停播 + 取消正在跑的回合（runTurn 内部会清缓冲上抛取消） */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        tts.stop()
        _state.value = ConvState.Idle
    }

    /** 首轮对话时从 Room 恢复历史滑窗（重启后仍记得最近 N 轮） */
    private suspend fun seedFromMemory() {
        if (seeded) return
        seeded = true
        val stored = runCatching { memory.loadRecentTurns(MAX_HISTORY) }.getOrDefault(emptyList())
        if (stored.isEmpty()) return
        for (t in stored) {
            messages.addLast(ChatMessage("user", t.user))
            messages.addLast(ChatMessage("assistant", t.reply))
        }
        _history.value = stored.map { ConvTurn(it.user, it.reply) }
    }

    /** system prompt = 基底人格 + 长期画像 + 近期每日摘要（每轮重建，语音编辑即时生效） */
    private suspend fun buildMemoryPrompt(): String {
        val profile = runCatching { memory.profileLines() }.getOrDefault(emptyList())
        val summaries = runCatching { memory.recentDailySummaries(RECENT_SUMMARIES) }
            .getOrDefault(emptyList())
            .map { it.date to it.summary }
        return MemoryPrompts.buildSystemPrompt(DEFAULT_SYSTEM_PROMPT, profile, summaries)
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val EAGER_FIRST_CHARS = 12
        private const val RECENT_SUMMARIES = 5
        const val DEFAULT_SYSTEM_PROMPT =
            "你是灵犀，一位简洁温暖、通过语音与人对话的中文助手。" +
                "回答要口语化、简短、直接说重点，通常不超过三句话；" +
                "避免 Markdown、列表和表情符号，因为你的话会被直接朗读出来。"
    }
}
