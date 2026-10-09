package com.lingxi.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.ConvState
import com.lingxi.data.ConvTurn
import com.lingxi.data.ConversationEngine
import com.lingxi.data.MicRecorder
import com.lingxi.data.ProviderRepository
import com.lingxi.data.UiAction
import com.lingxi.data.VoiceTurnRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConvUiState(
    val engineState: ConvState = ConvState.Idle,
    val history: List<ConvTurn> = emptyList(),
    val micGranted: Boolean = false,
    val hasProvider: Boolean = true,
    val recording: Boolean = false,
    /** 当前回合等待用户应答的 UI 原语（ChoiceSheet/ConfirmGate） */
    val pending: UiAction? = null,
)

/**
 * 对话页 VM：录音采集（IO 线程）→ 云 ASR → ConversationEngine 级联。
 * R2 交互形态 = 按住说话 + 文本输入兜底；常听门控 R3+ 接入。
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    application: Application,
    private val repo: ProviderRepository,
    private val engine: ConversationEngine,
    private val runner: VoiceTurnRunner,
) : AndroidViewModel(application) {

    private var recorder: MicRecorder? = null
    private var captureJob: Job? = null

    private val _ui = MutableStateFlow(
        ConvUiState(
            micGranted = hasMicPermission(),
        )
    )
    val ui: StateFlow<ConvUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            engine.state.collect { s -> _ui.value = _ui.value.copy(engineState = s) }
        }
        viewModelScope.launch {
            engine.history.collect { h -> _ui.value = _ui.value.copy(history = h) }
        }
        viewModelScope.launch {
            repo.providers.collect { list ->
                _ui.value = _ui.value.copy(hasProvider = list.any { it.enabled })
            }
        }
        viewModelScope.launch {
            engine.pendingUi.collect { p -> _ui.value = _ui.value.copy(pending = p) }
        }
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun onMicPermission(granted: Boolean) {
        _ui.value = _ui.value.copy(micGranted = granted)
    }

    /** 按下：开始录音（采集循环在 IO 线程）。正在播报/思考则先打断（barge-in） */
    fun startListening() {
        if (!_ui.value.micGranted || _ui.value.recording) return
        if (engine.state.value !is ConvState.Idle) engine.cancel()
        val rec = MicRecorder()
        if (!rec.start()) {
            _ui.value = _ui.value.copy(recording = false)
            return
        }
        recorder = rec
        _ui.value = _ui.value.copy(recording = true)
        captureJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && recorder === rec) {
                if (!rec.readMore()) break
            }
        }
    }

    /** 松开：停采集 →（去静音）→ 云 ASR → LLM 流式 → TTS 播报（与胶囊共用 runner） */
    fun stopListening() {
        val rec = recorder ?: return
        recorder = null
        _ui.value = _ui.value.copy(recording = false)
        viewModelScope.launch(Dispatchers.IO) {
            val (pcm, rate) = rec.stop()
            if (pcm.isEmpty()) {
                _ui.value = _ui.value.copy(engineState = ConvState.Failed("", "没有录到声音"))
                return@launch
            }
            if (!runner.runVoiceTurn(pcm, rate)) {
                _ui.value = _ui.value.copy(engineState = ConvState.Failed("", "请先在设置里配置一个 AI 服务"))
            }
        }
    }

    /** 文本输入兜底（无麦克风权限 / ASR 不可用 / 打字更快的场景） */
    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val provider = repo.firstEnabled()
            if (provider == null) {
                _ui.value = _ui.value.copy(engineState = ConvState.Failed("", "请先在设置里配置一个 AI 服务"))
                return@launch
            }
            val (config, key) = provider
            engine.runTurn(text, key, config.baseUrl, config.model)
        }
    }

    fun cancelTurn() {
        captureJob?.cancel()
        recorder = null
        _ui.value = _ui.value.copy(recording = false)
        engine.cancel()
    }

    /** Stream-UI 应答（手势通道）：ChoiceSheet 点选 */
    fun onUiChoice(index: Int) = engine.onUiChoice(index)

    /** Stream-UI 应答（ConfirmGate 确认/取消） */
    fun onUiConfirm(confirmed: Boolean) = engine.onUiConfirm(confirmed)
}
