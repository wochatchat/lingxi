package com.lingxi.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lingxi.MainActivity
import com.lingxi.data.BargeInGate
import com.lingxi.data.ContinuousMic
import com.lingxi.data.ConvState
import com.lingxi.data.ConversationEngine
import com.lingxi.data.EnergyVad
import com.lingxi.data.MicRecorder
import com.lingxi.data.SettingsRepository
import com.lingxi.data.TtsEngine
import com.lingxi.data.VadConfig
import com.lingxi.data.VadEvent
import com.lingxi.data.VoiceTurnRunner
import com.lingxi.data.rmsOfPcm
import com.lingxi.data.segmentValid
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 耳语胶囊（R3 常驻形态）：
 * - 前台服务保活（specialUse；常听开启时叠加 microphone 类型）。
 * - 悬浮胶囊：状态色随对话引擎变化、拖动、贴边吸附；点击展开对话卡片。
 * - 对话卡片：流式状态/最近一轮、按住说话（PTT 逃生）、打断、打开主界面、收起。
 * - 常听管线：连续采集 → 能量 VAD（G1）→ 语音段 → 云 ASR → 对话引擎；
 *   G0（服务级）= 常听开关 + 麦克风权限；TTS 播报/PTT 期间挂起 VAD（回声防护）。
 *
 * 生命周期：LingXiApp 收集开关（胶囊或常听任一开启 → start，全关 → stop）。
 */
@AndroidEntryPoint
class FloatingCapsuleService : Service() {

    @Inject lateinit var engine: ConversationEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var runner: VoiceTurnRunner
    @Inject lateinit var tts: TtsEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var windowManager: WindowManager
    private var capsuleView: TextView? = null
    private var cardView: LinearLayout? = null

    private var capsuleParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var capsuleStartX = 0f
    private var capsuleStartY = 0f
    private var dragging = false

    private val touchSlop: Float by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
    }

    @Volatile private var pauseOnScreenOff = false

    // 常听管线状态
    private var listenJob: Job? = null
    @Volatile private var pttRecording = false
    @Volatile private var alwaysListenOn = false

    /** F17 息屏全停：息屏暂停常听（省电），亮屏恢复 */
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (pauseOnScreenOff && alwaysListenOn) {
                        listenJob?.cancel()
                        listenJob = null
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (alwaysListenOn && listenJob == null) startAlwaysListen()
                }
                // R11 自启兜底：解锁后重试 mic 类型叠加 + 常听管线（开屏广播随服务常驻）
                Intent.ACTION_USER_PRESENT -> {
                    refreshForegroundType()
                    if (alwaysListenOn && listenJob == null) startAlwaysListen()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        refreshForegroundType()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        observeEngineState()
        observeCapsuleToggle()
        observeAlwaysListenToggle()
        observeScreenOffStop()

        // F17 息屏全停：监听屏幕开关（receiver 常驻本服务生命周期）
        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            // R11 自启兜底：解锁后补齐 mic 类型 FGS 叠加（BOOT 后系统禁止直接起 mic 类 FGS）
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { settings.setCapsuleEnabled(false); settings.setAlwaysListenEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenStateReceiver) }
        removeViews()
        listenJob?.cancel()
        listenJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 状态观察 ----------

    private fun observeEngineState() {
        scope.launch {
            engine.state.collect { state -> onConvStateChanged(state) }
        }
    }

    /** 胶囊开关 → 添加/移除悬浮视图（无悬浮窗权限时只提示一次） */
    private fun observeCapsuleToggle() {
        scope.launch {
            settings.capsuleEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) addCapsule() else removeViews()
            }
        }
    }

    /** 常听开关 → 启停常听管线（G0） */
    private fun observeAlwaysListenToggle() {
        scope.launch {
            settings.alwaysListenEnabled.distinctUntilChanged().collectLatest { enabled ->
                alwaysListenOn = enabled
                if (enabled) {
                    refreshForegroundType()
                    startAlwaysListen()
                } else {
                    listenJob?.cancel()
                    listenJob = null
                    refreshForegroundType()
                }
            }
        }
    }

    /** F17 息屏全停开关 → 停/启常听跟随 */
    private fun observeScreenOffStop() {
        scope.launch {
            settings.screenOffStopEnabled.distinctUntilChanged().collect { enabled ->
                pauseOnScreenOff = enabled
                if (!enabled && alwaysListenOn && listenJob == null) startAlwaysListen()
            }
        }
    }

    // ---------- 常听管线（连续采集 → 能量 VAD → 语音段 → ASR → 引擎） ----------

    /** 挂起条件（回声防护）：PTT 录音中，或引擎处于 识别/思考/出错 态。 */
    private fun vadSuppressed(): Boolean =
        pttRecording || engine.state.value !is ConvState.Idle

    private fun startAlwaysListen() {
        if (listenJob != null) return
        if (!micGranted()) return
        listenJob = scope.launch(Dispatchers.IO) {
            // F17 可调 VAD 阈值（降档省电）：调高阈值 = 更难触发，灵敏度降低
            val thresholdRms = settings.vadThreshold.first().toDouble().coerceIn(100.0, 5000.0)
            val mic = ContinuousMic()
            if (!mic.start()) { listenJob = null; return@launch }
            val vad = EnergyVad(VadConfig(thresholdRms = thresholdRms))
            val prebuf = ArrayDeque<ByteArray>()       // 语音起点前 600ms 预缓冲
            var collected = mutableListOf<ByteArray>() // 当前语音段
            var speechMs = 0
            val bargeGate = BargeInGate()
            var bargeCooldownUntil = 0L
            try {
                while (isActive) {
                    val data = mic.read() ?: break
                    val rms = rmsOfPcm(data)
                    val state = engine.state.value
                    when {
                        // 挂起：PTT / 识别 / 思考 / 出错——只排空缓冲不判定（回声防护）
                        pttRecording || (state !is ConvState.Idle && state !is ConvState.Speaking) -> {
                            vad.reset()
                            bargeGate.reset()
                            prebuf.clear()
                            collected = mutableListOf()
                            speechMs = 0
                        }
                        // 播报中：barge-in 窗口——持续人声覆盖播报则立即打断
                        state is ConvState.Speaking -> {
                            vad.reset()
                            prebuf.clear()
                            if (bargeGate.feed(rms, ContinuousMic.CHUNK_MS)) {
                                engine.cancel()
                                bargeCooldownUntil = System.currentTimeMillis() + BARGE_IN_COOLDOWN_MS
                                collected = mutableListOf()
                                speechMs = 0
                            }
                        }
                        // 打断后冷却：等喇叭回声尾音衰减再恢复常听判定
                        System.currentTimeMillis() < bargeCooldownUntil -> {
                            vad.reset()
                            prebuf.clear()
                        }
                        else -> {
                            prebuf.addLast(data)
                            while (prebuf.size > PREBUF_CHUNKS) prebuf.removeFirst()
                            when (vad.feed(rms, ContinuousMic.CHUNK_MS)) {
                                VadEvent.SpeechStart -> {
                                    collected = prebuf.toMutableList()
                                    prebuf.clear()
                                    speechMs = 0
                                }
                                VadEvent.SpeechEnd -> {
                                    finishSegment(collected)
                                    collected = mutableListOf()
                                    speechMs = 0
                                }
                                null -> if (collected.isNotEmpty()) {
                                    collected.add(data)
                                    speechMs += ContinuousMic.CHUNK_MS
                                    if (speechMs >= MAX_SEGMENT_MS) { // 30s 上限强制收段
                                        finishSegment(collected)
                                        collected = mutableListOf()
                                        speechMs = 0
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                mic.stop()
                listenJob = null
            }
        }
    }

    /** 语音段收口：过短（噪声）丢弃；否则起协程跑一轮语音对话 */
    private fun finishSegment(collected: List<ByteArray>) {
        val total = collected.sumOf { it.size }
        val durationMs = total / 2 * 1000 / ContinuousMic.SAMPLE_RATE
        if (!segmentValid(durationMs)) return
        val pcm = ByteArray(total)
        var off = 0
        for (c in collected) { c.copyInto(pcm, off); off += c.size }
        scope.launch(Dispatchers.IO) { runner.runVoiceTurn(pcm, ContinuousMic.SAMPLE_RATE) }
    }

    // ---------- 按住说话（PTT 逃生通道，卡片上） ----------

    private fun startPtt() {
        if (pttRecording) return
        if (!micGranted()) return
        if (engine.state.value !is ConvState.Idle) engine.cancel()
        val rec = MicRecorder()
        if (!rec.start()) return
        recPtt = rec
        pttRecording = true
        scope.launch(Dispatchers.IO) {
            while (isActive && pttRecording) {
                if (!rec.readMore()) break
            }
        }
    }

    private fun stopPtt() {
        if (!pttRecording) return
        pttRecording = false
        val rec = recPtt
        recPtt = null
        scope.launch(Dispatchers.IO) {
            if (rec == null) return@launch
            val (pcm, rate) = rec.stop()
            if (pcm.isNotEmpty()) runner.runVoiceTurn(pcm, rate)
        }
    }

    private var recPtt: MicRecorder? = null

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- 悬浮视图 ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    @SuppressLint("ClickableViewAccessibility")
    private fun addCapsule(): Boolean {
        if (capsuleView != null) return true
        if (!hasOverlayPermission()) return false
        val capsule = TextView(this).apply {
            text = "灵"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(capsuleColorFor(engine.state.value))
            }
        }
        val size = dp(CAPSULE_SIZE_DP)
        capsuleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = CapsuleMath.snapTargetX(0f, screenWidth, size).toInt()
            y = screenHeight / 3
        }
        capsule.setOnTouchListener(capsuleTouchListener)
        capsule.setOnClickListener { toggleCard() }
        return try {
            windowManager.addView(capsule, capsuleParams)
            capsuleView = capsule
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 胶囊触摸：拖动跟随 + 松手贴边；未拖动则交给 click 展开卡片 */
    private val capsuleTouchListener = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                capsuleStartX = (capsuleParams?.x ?: 0).toFloat()
                capsuleStartY = (capsuleParams?.y ?: 0).toFloat()
                dragging = false
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val p = capsuleParams ?: return@OnTouchListener false
                val dx = event.rawX - touchDownRawX
                val dy = event.rawY - touchDownRawY
                if (!dragging && CapsuleMath.exceededSlop(dx, dy, touchSlop)) dragging = true
                if (dragging) {
                    val size = dp(CAPSULE_SIZE_DP)
                    p.x = CapsuleMath.clamp(capsuleStartX + dx, 0f, (screenWidth - size).toFloat()).toInt()
                    p.y = CapsuleMath.clamp(capsuleStartY + dy, 0f, (screenHeight - size).toFloat()).toInt()
                    windowManager.updateViewLayout(v, p)
                }
                dragging
            }
            MotionEvent.ACTION_UP -> {
                val p = capsuleParams
                if (dragging && p != null) {
                    val size = dp(CAPSULE_SIZE_DP)
                    p.x = CapsuleMath.snapTargetX(p.x.toFloat(), screenWidth, size).toInt()
                    windowManager.updateViewLayout(v, p)
                }
                dragging
            }
            else -> false
        }
    }

    /** 点击胶囊：展开/收起对话卡片（卡片朝屏幕内侧展开） */
    private fun toggleCard() {
        if (cardView != null) {
            removeCard()
            return
        }
        if (!hasOverlayPermission()) return
        val size = dp(CAPSULE_SIZE_DP)
        val capsule = capsuleParams ?: return
        val onLeftEdge = capsule.x + size / 2 <= screenWidth / 2
        val cardWidth = dp(CARD_WIDTH_DP)
        val card = buildCard()
        val margin = dp(8)
        cardParams = WindowManager.LayoutParams(
            cardWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onLeftEdge) capsule.x + size + margin
            else (capsule.x - cardWidth - margin).coerceAtLeast(margin)
            y = capsule.y.coerceIn(0, (screenHeight - dp(180)).coerceAtLeast(0))
        }
        try {
            windowManager.addView(card, cardParams)
            cardView = card
            onConvStateChanged(engine.state.value) // 展开即刷当前状态
        } catch (e: Exception) {
            cardView = null
        }
    }

    private fun buildCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE61B1B1F.toInt())
            }
        }
        val title = TextView(this).apply {
            text = "灵犀 · 待命中"
            id = android.R.id.text1
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(this).apply {
            id = android.R.id.text2
            setTextColor(0xFFBDBDC4.toInt())
            textSize = 13f
            setMaxLines(6)
        }
        val mic = TextView(this).apply {
            text = "🎤 按住说话"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF2E7D32.toInt())
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startPtt(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopPtt(); true }
                    else -> false
                }
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pill(label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(0xFFBDBDC4.toInt())
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFF2A2A30.toInt())
                }
                setOnClickListener { onClick() }
            }
        row.addView(pill("打断") { engine.cancel() })
        row.addView(pill("主界面") { openMain() })
        row.addView(pill("收起") { removeCard() })
        card.addView(title)
        card.addView(body)
        card.addView(mic)
        card.addView(row)
        return card
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        removeCard()
    }

    private fun removeCard() {
        cardView?.let { runCatching { windowManager.removeView(it) } }
        cardView = null
    }

    private fun removeViews() {
        removeCard()
        capsuleView?.let { runCatching { windowManager.removeView(it) } }
        capsuleView = null
    }

    // ---------- 状态渲染与通知 ----------

    /** 引擎状态变化：胶囊变色 + 卡片标题/正文刷新 */
    private fun onConvStateChanged(state: ConvState) {
        (capsuleView?.background as? GradientDrawable)?.setColor(capsuleColorFor(state))
        val card = cardView ?: return
        val title = card.findViewById<TextView>(android.R.id.text1)
        val body = card.findViewById<TextView>(android.R.id.text2) ?: return
        when (state) {
            is ConvState.Idle -> {
                title?.text = "灵犀 · 待命中"
                body.text = engine.history.value.lastOrNull()?.reply?.takeLast(160) ?: "点胶囊外按住说话，或直接对我说"
            }
            is ConvState.Transcribing -> {
                title?.text = "灵犀 · 识别中"
                body.text = "正在听你说话…"
            }
            is ConvState.Thinking -> {
                title?.text = "灵犀 · 思考中"
                body.text = state.partial.ifBlank { "思考中…" }
            }
            is ConvState.Speaking -> {
                title?.text = "灵犀 · 播报中"
                body.text = state.partial
            }
            is ConvState.Failed -> {
                title?.text = "灵犀 · 出错了"
                body.text = state.message
            }
        }
    }

    /** 前台服务类型：specialUse 恒有；常听开启且已授权麦克风时叠加 microphone */
    private fun refreshForegroundType() {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        var type = base
        if (alwaysListenOn && micGranted()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.onFailure {
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), base)
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingCapsuleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("灵犀正在待命")
            .setContentText("点按打开 · 常听与胶囊可在设置中关闭")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "灵犀常驻",
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lingxi_capsule"
        private const val NOTIFICATION_ID = 1001

        /** R11 保活心跳用：服务进程内存活标志（KeepAliveWorker 判断是否需要拉起） */
        @Volatile var isRunning: Boolean = false
            private set
        private const val CAPSULE_SIZE_DP = 46
        private const val CARD_WIDTH_DP = 250
        private const val PREBUF_CHUNKS = 6          // 语音起点前 600ms 预缓冲
        private const val MAX_SEGMENT_MS = 30_000    // 单段语音上限
        private const val BARGE_IN_COOLDOWN_MS = 500 // 打断后等回声尾音衰减

        const val ACTION_STOP = "com.lingxi.action.CAPSULE_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FloatingCapsuleService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCapsuleService::class.java))
        }
    }
}

