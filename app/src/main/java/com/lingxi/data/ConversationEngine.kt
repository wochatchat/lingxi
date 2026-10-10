package com.lingxi.data

import com.lingxi.data.functions.ActionExecutor
import com.lingxi.data.functions.ActionResult
import com.lingxi.data.functions.ToolDefs
import com.lingxi.data.memory.MemoryPrompts
import com.lingxi.data.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 对话回合状态（级联管线：ASR → LLM 流式 → 句切分 → TTS 队列播报） */
sealed interface ConvState {
    data object Idle : ConvState
    data object Transcribing : ConvState
    data class Thinking(val partial: String) : ConvState
    data class Speaking(val partial: String) : ConvState
    data class Failed(val user: String, val message: String) : ConvState
}

/** 一轮已完成的对话（UI 历史展示，ui = 挂靠在本轮的 Stream-UI 卡片，可回放） */
data class ConvTurn(val user: String, val reply: String, val ui: List<UiAction> = emptyList())

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
    private val executor: ActionExecutor,
    private val captor: com.lingxi.data.screen.ScreenCaptor,
) {
    private val _state = MutableStateFlow<ConvState>(ConvState.Idle)
    val state: StateFlow<ConvState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ConvTurn>>(emptyList())
    val history: StateFlow<List<ConvTurn>> = _history.asStateFlow()

    /** 当前回合正在等待用户响应的 UI 原语（ChoiceSheet/ConfirmGate），null = 无 */
    private val _pendingUi = MutableStateFlow<UiAction?>(null)
    val pendingUi: StateFlow<UiAction?> = _pendingUi.asStateFlow()

    private val messages = ArrayDeque<ChatMessage>()

    /** 正在跑的回合协程：cancel 时连根取消，防止旧回合在打断后继续落账/改状态 */
    @Volatile private var currentJob: Job? = null

    /** 会话滑窗是否已从 Room 恢复（进程生命周期内只恢复一次） */
    @Volatile private var seeded = false

    /** 用户对 pendingUi 的应答（确认/取消/选择索引） */
    @Volatile private var pendingAnswer: CompletableDeferred<String>? = null

    /** F16 全离线模式（LingXiApp 收集设置后写入；引擎不直接依赖 DataStore） */
    @Volatile var offlineMode: Boolean = false
        private set

    /** 由应用层同步全离线开关 */
    fun setOfflineMode(enabled: Boolean) { offlineMode = enabled }

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
            // 语音应答通道：有 pendingUi 时，用户的话优先解析为对它的应答（语音+手势双通道）
            pendingUi.value?.let { pending ->
                val resolved = resolvePendingFromText(user)
                if (resolved != null) {
                    pendingAnswer?.complete(resolved)
                    return
                }
                // 不是应答：当作新输入，取消挂起中的原语（TTL 之外的显式取消）
                pendingAnswer?.complete(CANCELLED)
                _pendingUi.value = null
            }

            // F5 意图路由：DIRECT 命中 → 零 token 直接执行
            val route = IntentRouter.route(user)
            // F10 截屏问答：确认后随本轮消息多模态上行
            var attachedImages: List<ChatImage> = emptyList()
            when (route) {
                is IntentRouter.Route.Direct -> {
                    finishDirectTurn(user, route)
                    return
                }
                IntentRouter.Route.ToolsLlm, IntentRouter.Route.Chat, IntentRouter.Route.Screenshot -> {
                    // F16 全离线模式：云端对话停用，仅系统动作（上面 DIRECT 分支）可用
                    if (offlineMode) {
                        finishOfflineTurn(user)
                        return
                    }
                    if (route == IntentRouter.Route.Screenshot) {
                        val image = prepareScreenshotImage(user) ?: return
                        attachedImages = listOf(image)
                    }
                }
            }
            seedFromMemory()
            val systemPrompt = buildMemoryPrompt()
            val request = buildList {
                add(ChatMessage("system", systemPrompt))
                addAll(messages.toList())
                add(ChatMessage("user", user, attachedImages))
            }
            _state.value = ConvState.Thinking("")
            // 首句预读：12 字内遇停顿即切出，TTS 队列天然预读后续句（PRD「提前 2 句预读」基座）
            val splitter = SentenceSplitter(eagerFirstSplitChars = EAGER_FIRST_CHARS)
            var anyEnqueued = false
            val uiActions = mutableListOf<UiAction>()
            val tools = if (route == IntentRouter.Route.ToolsLlm) ToolDefs.ALL else null
            llm.streamChat(apiKey, baseUrl, model, request, tools = tools).collect { ev ->
                when (ev) {
                    is LlmEvent.Delta -> {
                        reply.append(ev.text)
                        for (sentence in splitter.feed(ev.text)) {
                            tts.enqueue(stripMarkdownForSpeech(sentence))
                            anyEnqueued = true
                        }
                        _state.value = ConvState.Thinking(reply.toString())
                    }
                    is LlmEvent.ToolCall -> {
                        val (spoken, action) = handleToolCall(ev)
                        if (spoken.isNotBlank()) {
                            tts.enqueue(stripMarkdownForSpeech(spoken))
                            anyEnqueued = true
                        }
                        action?.let { uiActions.add(it) }
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
            _history.value = _history.value + ConvTurn(user, replyText, uiActions.toList())
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

    /** F16 全离线占位回复：云端对话停用，系统动作（DIRECT）仍可执行 */
    private suspend fun finishOfflineTurn(user: String) {
        _state.value = ConvState.Thinking("")
        val msg = "现在是全离线模式，联网对话已停用；设闹钟、打开应用、定提醒这类系统操作我仍然可以直接执行。"
        tts.enqueue(msg)
        finishTurnWithAction(user, msg, null)
    }

    /** F10 截屏问答准备：取最近截图 + 上传确认门；返回 null 表示本轮已收尾（没截图/用户取消） */
    private suspend fun prepareScreenshotImage(user: String): ChatImage? {
        _state.value = ConvState.Thinking("")
        if (!captor.hasPermission()) {
            val msg = "读取截图需要照片权限，请在设置里授权后重试"
            tts.enqueue(msg)
            finishTurnWithAction(user, msg, null)
            return null
        }
        val shot = runCatching { captor.latestScreenshot() }.getOrNull()
        if (shot == null) {
            val msg = "没找到最近的截图，请先截一张屏，再对我说「看看屏幕上这个」"
            tts.enqueue(msg)
            finishTurnWithAction(user, msg, null)
            return null
        }
        val gate = UiAction(
            type = UiAction.UiType.ConfirmGate,
            title = "截屏问答",
            body = "要把最近一张截图上传给 AI 分析吗？（截图内容会发送到所配置的 AI 服务，注意隐私）",
        )
        tts.enqueue(stripMarkdownForSpeech(UiAction.confirmVoicePrompt(gate)))
        val answer = askUser(gate)
        if (answer != CONFIRMED) {
            val msg = "好的，先不上传截图"
            tts.enqueue(msg)
            finishTurnWithAction(user, msg, gate.copy(resolvedText = "已取消"))
            return null
        }
        return ChatImage(mime = shot.mime, base64 = shot.base64)
    }

    /** 语音输入完整一轮：ASR → runTurn（ASR 失败不进对话） */
    suspend fun runVoiceTurn(        audio: ByteArray,
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
        pendingAnswer?.complete(CANCELLED)
        _pendingUi.value = null
        tts.stop()
        _state.value = ConvState.Idle
    }

    /** UI 通道应答（ChoiceSheet 点选 / ConfirmGate 确认取消），由 UI 层调用 */
    fun onUiChoice(index: Int) { pendingAnswer?.complete(index.toString()) }
    fun onUiConfirm(confirmed: Boolean) { pendingAnswer?.complete(if (confirmed) CONFIRMED else CANCELLED) }

    /** 把用户语音/文本解析为对 pendingUi 的应答；不是应答则返回 null（当普通输入处理） */
    private fun resolvePendingFromText(text: String): String? {
        val pending = pendingUi.value ?: return null
        return when (pending.type) {
            UiAction.UiType.ChoiceSheet -> UiAction.parseVoiceChoice(text)?.toString()
            UiAction.UiType.ConfirmGate -> UiAction.parseConfirm(text)?.let { if (it) "1" else "0" }
            UiAction.UiType.InfoCard -> null
        }
    }

    /**
     * 处理 LLM tool_call 事件（含 send_sms 确认门 / open_app 多候选点选）。
     * 返回 (口语播报, 要挂到本轮的 UI 卡片)。
     */
    private suspend fun handleToolCall(ev: LlmEvent.ToolCall): Pair<String, UiAction?> {
        // ui_action 工具：LLM 自发下发 UI 意图（Stream-UI 的 LLM 驱动入口）
        if (ev.name == "ui_action") {
            return showUiAction(ev.argsJson)
        }
        // F9 代点：永远先过确认门（GuardedIO：人按最后一键）
        if (ev.name == "click_ui") {
            val target = runCatching {
                Json.parseToJsonElement(ev.argsJson.ifBlank { "{}" }).jsonObject
            }.getOrNull()?.get("target")?.jsonPrimitive?.contentOrNull.orEmpty()
            val gate = UiAction(
                type = UiAction.UiType.ConfirmGate,
                title = "UI 代操作",
                body = "要在当前屏幕点击「$target」吗？",
            )
            tts.enqueue(stripMarkdownForSpeech(UiAction.confirmVoicePrompt(gate)))
            val answer = askUser(gate)
                ?: return "超时了，先不点" to gate.copy(resolvedText = "超时未确认")
            if (answer != CONFIRMED) {
                return "好的，先不点" to gate.copy(resolvedText = "已取消")
            }
            val clickResult = runCatching { executor.execute("click_ui", ev.argsJson) }
                .getOrElse { ActionResult.error(it.message ?: "执行失败") }
            tts.enqueue(stripMarkdownForSpeech(clickResult.spoken))
            return "" to UiAction(
                type = UiAction.UiType.InfoCard,
                title = "UI 代操作",
                body = if (clickResult.success) "已点击：$target" else (clickResult.errorMessage ?: "点击失败"),
            )
        }
        // send_sms 走 ConfirmGate（安全边界：短信必须过确认门）
        if (ev.name == "send_sms") {
            val args = runCatching {
                Json.parseToJsonElement(ev.argsJson.ifBlank { "{}" }).jsonObject
            }.getOrNull()
            val phone = args?.get("phone")?.jsonPrimitive?.contentOrNull ?: ""
            val message = args?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
            val gate = UiAction(
                type = UiAction.UiType.ConfirmGate,
                title = "发送短信",
                body = "收件人 $phone：$message",
            )
            tts.enqueue(stripMarkdownForSpeech(UiAction.confirmVoicePrompt(gate)))
            val answer = askUser(gate) ?: return "好的，先不发短信" to gate.copy(resolvedText = "已取消")
            if (answer == "0") return "好的，不发了" to gate.copy(resolvedText = "已取消")
            val smsResult = runCatching { executor.execute("send_sms", ev.argsJson) }
                .getOrElse { ActionResult.error(it.message ?: "执行失败") }
            tts.enqueue(stripMarkdownForSpeech(smsResult.spoken))
            return "" to gate.copy(resolvedText = "已发送（请在短信应用点发送）")
        }
        val first = runCatching { executor.execute(ev.name, ev.argsJson) }
            .getOrElse { ActionResult.error(it.message ?: "执行失败") }
        // open_app 多候选 → ChoiceSheet，等用户说"第 N 个"
        if (first.candidates.isNotEmpty()) {
            val sheet = UiAction(
                type = UiAction.UiType.ChoiceSheet,
                title = "找到多个匹配的应用",
                options = first.candidates,
            )
            tts.enqueue(stripMarkdownForSpeech(UiAction.voicePrompt(sheet)))
            val chosen = askUser(pending = sheet) ?: return "那先不打开了，需要再说一声" to null
            val idx = chosen.toIntOrNull()
            if (idx == null || idx < 0 || idx >= first.candidates.size) {
                return "好的，先不打开了" to UiAction(
                    type = UiAction.UiType.ChoiceSheet, title = "打开应用",
                    options = first.candidates, resolvedIndex = -1, resolvedText = "已取消",
                )
            }
            val name = first.candidates[idx]
            val launch = executor.execute("open_app", """{"app_name":"$name"}""")
            tts.enqueue(stripMarkdownForSpeech(launch.spoken))
            return "" to UiAction(
                type = UiAction.UiType.ChoiceSheet, title = "打开应用",
                options = first.candidates, resolvedIndex = idx, resolvedText = name,
            )
        }
        // 其余工具直接执行（单结果）
        if (first.spoken.isNotBlank()) tts.enqueue(stripMarkdownForSpeech(first.spoken))
        val card = first.cardTitle?.let {
            UiAction(type = UiAction.UiType.InfoCard, title = it, body = first.cardBody)
        }
        return "" to card
    }

    /** 处理 LLM 下发的 ui_action 工具调用：呈现原语并等用户应答 */
    private suspend fun showUiAction(argsJson: String): Pair<String, UiAction?> {
        val parsed = parseUiActionArgs(argsJson)
            ?: return "好的。" to UiAction(type = UiAction.UiType.InfoCard, title = "提示", body = "参数解析失败")
        val action = parsed
        return when (action.type) {
            UiAction.UiType.ChoiceSheet -> {
                tts.enqueue(stripMarkdownForSpeech(UiAction.voicePrompt(action)))
                val answer = askUser(action) ?: return "超时了，先这样" to null
                val idx = answer.toIntOrNull()
                val chosen = idx?.let { action.options.getOrNull(it) }
                if (chosen == null) "好的，先不选了" to action.copy(resolvedIndex = -1, resolvedText = "已取消")
                else "好的，选了 $chosen" to action.copy(resolvedIndex = idx, resolvedText = chosen)
            }
            UiAction.UiType.ConfirmGate -> {
                tts.enqueue(stripMarkdownForSpeech(UiAction.confirmVoicePrompt(action)))
                val answer = askUser(action) ?: return "超时了，先不执行" to action.copy(resolvedText = "超时未确认")
                if (answer == "1") "好的" to action.copy(resolvedText = "已确认")
                else "好的，先不做" to action.copy(resolvedText = "已取消")
            }
            UiAction.UiType.InfoCard -> "" to action
        }
    }

    /** 挂起等待用户应答（TTL 超时自动取消，对话永不卡死） */
    private suspend fun askUser(pending: UiAction): String? {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        _pendingUi.value = pending
        try {
            return withTimeoutOrNull(pending.ttlMs) { deferred.await() }
        } finally {
            _pendingUi.value = null
            pendingAnswer = null
        }
    }

    /** F5 DIRECT 路径：执行系统动作 → 口播确认 → 落账（含 InfoCard） */
    private suspend fun finishDirectTurn(
        user: String,
        route: IntentRouter.Route.Direct,
    ) {
        _state.value = ConvState.Thinking("")
        seedFromMemory()
        val result = runCatching {
            executor.execute(route.toolName, toJsonArgs(route.args))
        }.getOrElse { ActionResult.error(it.message ?: "执行失败") }
        // 候选列表 → ChoiceSheet（open_app 多匹配）
        if (result.candidates.isNotEmpty()) {
            val sheet = UiAction(
                type = UiAction.UiType.ChoiceSheet,
                title = "找到多个应用",
                options = result.candidates,
            )
            tts.enqueue(stripMarkdownForSpeech(UiAction.voicePrompt(sheet)))
            val answer = askUser(sheet)
            val idx = answer?.toIntOrNull()
            val chosen = idx?.let { result.candidates.getOrNull(it) }
            val final = if (chosen == null) {
                tts.enqueue("好的，先不打开")
                sheet.copy(resolvedIndex = -1, resolvedText = "已取消")
            } else {
                val r = runCatching { executor.execute("open_app", """{"app_name":"$chosen"}""") }
                    .getOrElse { ActionResult.error(it.message ?: "打开失败") }
                tts.enqueue(stripMarkdownForSpeech(r.spoken))
                sheet.copy(resolvedIndex = idx, resolvedText = chosen)
            }
            finishTurnWithAction(user, "好的", sheet.copy(resolvedIndex = final.resolvedIndex, resolvedText = final.resolvedText))
            return
        }
        val card = result.cardTitle?.let {
            UiAction(type = UiAction.UiType.InfoCard, title = it, body = result.cardBody)
        }
        if (result.spoken.isNotBlank()) tts.enqueue(stripMarkdownForSpeech(result.spoken))
        finishTurnWithAction(user, result.spoken, card)
    }

    /** 直执行/工具轮的收尾：落账（工具轮的 reply 用动作播报兜底）并归位状态 */
    private suspend fun finishTurnWithAction(user: String, spoken: String, card: UiAction?) {
        tts.awaitIdle()
        messages.addLast(ChatMessage("user", user))
        messages.addLast(ChatMessage("assistant", spoken))
        while (messages.size > MAX_HISTORY * 2) messages.removeFirst()
        _history.value = _history.value + ConvTurn(user, spoken, listOfNotNull(card))
        runCatching { memory.onTurnCompleted(user, spoken) }
        _state.value = ConvState.Idle
    }

    /** args Map → 简单 JSON 对象（值全部字符串化，executor 端按需 toInt） */
    private fun toJsonArgs(args: Map<String, String>): String {
        val obj = buildMap {
            args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return JsonObject(obj).toString()
    }

    /** 解析 ui_action 工具参数（纯解析，失败返回 InfoCard 兜底） */
    private fun parseUiActionArgs(json: String): UiAction? {
        return runCatching {
            val obj = Json.parseToJsonElement(json.trim()).jsonObject
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "ChoiceSheet" -> UiAction.UiType.ChoiceSheet
                "ConfirmGate" -> UiAction.UiType.ConfirmGate
                else -> UiAction.UiType.InfoCard
            }
            UiAction(
                type = type,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "灵犀",
                body = obj["body"]?.jsonPrimitive?.contentOrNull,
                options = (obj["options"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
                confirmLabel = obj["confirm_label"]?.jsonPrimitive?.contentOrNull ?: "确认",
                cancelLabel = obj["cancel_label"]?.jsonPrimitive?.contentOrNull ?: "取消",
                ttlMs = obj["ttl_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 60_000L,
            )
        }.getOrNull()
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

        /** pendingUi 应答哨兵值（非索引非确认，仅表示"用户取消/打断"） */
        const val CANCELLED = "__cancelled__"
        const val CONFIRMED = "1"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是灵犀，一位简洁温暖、通过语音与人对话的中文助手。" +
                "回答要口语化、简短、直接说重点，通常不超过三句话；" +
                "避免 Markdown、列表和表情符号，因为你的话会被直接朗读出来。"
    }
}
