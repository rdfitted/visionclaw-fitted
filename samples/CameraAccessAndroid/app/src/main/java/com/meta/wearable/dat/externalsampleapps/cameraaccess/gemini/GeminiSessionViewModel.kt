package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.PendingConfirmation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionContextRefreshTracker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawNotification
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawNotificationKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.ResponseMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import java.util.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val pendingConfirmation: PendingConfirmation? = null,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
    val effectiveResponseMode: ResponseMode = ResponseMode.NORMAL,
)

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"
        private const val MAX_QUEUED_CRON_NOTIFICATIONS = 3
        private const val MAX_SESSION_CONTEXT_CHARS = 800
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val openClawBridge = OpenClawBridge()
    private val sessionStateManager = SessionStateManager(openClawBridge)
    private val _streamingMode = MutableStateFlow(StreamingMode.GLASSES)
    private val _effectiveResponseMode = MutableStateFlow(SettingsManager.responseModeFlow.value)
    val effectiveResponseMode: StateFlow<ResponseMode> = _effectiveResponseMode.asStateFlow()
    private val geminiService = object : GeminiLiveService() {
        override fun buildSystemInstruction(): String {
            return this@GeminiSessionViewModel.buildSystemInstruction()
        }

        override fun triggerReconnectAfterModeChange(reason: String) {
            this@GeminiSessionViewModel.connectGemini(startAuxiliaryServices = false)
        }
    }.apply {
        this.sessionStateManager = this@GeminiSessionViewModel.sessionStateManager
    }
    private val sessionContextRefreshTracker = SessionContextRefreshTracker(sessionStateManager, geminiService)
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private val queuedCronNotifications = ArrayDeque<OpenClawNotification>()
    private var lastVideoFrameTime: Long = 0
    private var stateObservationJob: Job? = null
    private var toolCallRouter: ToolCallRouter? = null
    private var previousPendingConfirmationId: String? = null

    var streamingMode: StreamingMode
        get() = _streamingMode.value
        set(value) {
            _streamingMode.value = value
        }

    init {
        observeEffectiveResponseMode()
    }

    fun startSession() {
        if (_uiState.value.isGeminiActive) return

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        sessionStateManager.reset(openClawBridge.operatorSessionId)
        sessionContextRefreshTracker.markCurrentContextSent()
        queuedCronNotifications.clear()
        previousPendingConfirmationId = null
        _uiState.value = _uiState.value.copy(
            isGeminiActive = true,
            effectiveResponseMode = effectiveResponseMode.value
        )

        // Wire audio callbacks
        audioManager.onAudioCaptured = lambda@{ data ->
            // Phone mode: mute mic while model speaks to prevent echo
            if (streamingMode == StreamingMode.PHONE && geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onTurnComplete = {
            sessionStateManager.updateObjective(_uiState.value.userTranscript)
            sessionContextRefreshTracker.sendRefreshIfChanged()
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        geminiService.onInputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
        }

        geminiService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
        }

        geminiService.onDisconnected = { reason ->
            if (_uiState.value.isGeminiActive) {
                stopSession()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Connection lost: ${reason ?: "Unknown error"}"
                )
            }
        }

        // Check OpenClaw and start session
        viewModelScope.launch {
            openClawBridge.checkConnection()
            val router = ToolCallRouter(
                bridge = openClawBridge,
                scope = viewModelScope,
                sessionStateManager = sessionStateManager
            )
            toolCallRouter = router
            sessionStateManager.setToolCallRouter(router)

            geminiService.onToolCall = { toolCall ->
                viewModelScope.launch {
                    for (call in toolCall.functionCalls) {
                        router.dispatch(call) { response ->
                            geminiService.sendToolResponse(response)
                        }
                    }
                }
            }

            geminiService.onToolCallCancellation = { cancellation ->
                router.cancelToolCalls(cancellation.ids)
            }

            stateObservationJob = viewModelScope.launch {
                combine(
                    geminiService.connectionState,
                    geminiService.isModelSpeaking,
                    openClawBridge.lastToolCallStatus,
                    openClawBridge.connectionState,
                    sessionStateManager.pendingConfirmation
                ) { connectionState, isModelSpeaking, toolCallStatus, openClawConnectionState, pendingConfirmation ->
                    GeminiUiState(
                        connectionState = connectionState,
                        isModelSpeaking = isModelSpeaking,
                        toolCallStatus = toolCallStatus,
                        pendingConfirmation = pendingConfirmation,
                        openClawConnectionState = openClawConnectionState,
                        effectiveResponseMode = _effectiveResponseMode.value
                    )
                }.collect { observedState ->
                    val hadPendingConfirmation = previousPendingConfirmationId != null
                    val hasPendingConfirmation = observedState.pendingConfirmation != null
                    previousPendingConfirmationId = observedState.pendingConfirmation?.id

                    _uiState.update { current ->
                        current.copy(
                            connectionState = observedState.connectionState,
                            isModelSpeaking = observedState.isModelSpeaking,
                            toolCallStatus = observedState.toolCallStatus,
                            pendingConfirmation = observedState.pendingConfirmation,
                            openClawConnectionState = observedState.openClawConnectionState,
                            effectiveResponseMode = observedState.effectiveResponseMode,
                        )
                    }

                    if (hadPendingConfirmation && !hasPendingConfirmation) {
                        flushQueuedCronNotifications()
                    }
                }
            }

            if (SettingsManager.proactiveNotificationsEnabled) {
                eventClient.onNotification = notification@{ notification ->
                    val state = _uiState.value
                    if (!state.isGeminiActive || state.connectionState != GeminiConnectionState.Ready) {
                        return@notification
                    }

                    if (sessionStateManager.pendingConfirmation.value != null) {
                        when (notification.kind) {
                            OpenClawNotificationKind.HEARTBEAT -> Unit
                            OpenClawNotificationKind.CRON -> enqueueCronNotification(notification)
                        }
                    } else {
                        geminiService.sendTextMessage(notification.text)
                    }
                }
            }

            connectGemini(startAuxiliaryServices = true)
        }
    }

    fun stopSession() {
        eventClient.disconnect()
        queuedCronNotifications.clear()
        previousPendingConfirmationId = null
        stateObservationJob?.cancel()
        stateObservationJob = null
        audioManager.stopCapture()
        geminiService.disconnect()
        sessionStateManager.reset(openClawBridge.operatorSessionId)
        sessionStateManager.setToolCallRouter(null)
        toolCallRouter = null
        _uiState.value = GeminiUiState()
    }

    fun confirmPendingAction(pendingActionId: String) {
        viewModelScope.launch {
            toolCallRouter?.confirmPendingAction(pendingActionId)
        }
    }

    fun cancelPendingAction(pendingActionId: String) {
        viewModelScope.launch {
            toolCallRouter?.cancelPendingAction(pendingActionId, "user_declined")
        }
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        if (!SettingsManager.videoStreamingEnabled) return
        if (!_uiState.value.isGeminiActive) return
        if (_uiState.value.connectionState != GeminiConnectionState.Ready) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < GeminiConfig.VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        geminiService.sendVideoFrame(bitmap)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        stopSession()
        sessionStateManager.shutdown()
        openClawBridge.shutdown()
        super.onCleared()
    }

    private fun enqueueCronNotification(notification: OpenClawNotification) {
        if (queuedCronNotifications.size >= MAX_QUEUED_CRON_NOTIFICATIONS) {
            queuedCronNotifications.removeFirst()
        }
        queuedCronNotifications.addLast(notification)
    }

    private fun flushQueuedCronNotifications() {
        val state = _uiState.value
        if (!state.isGeminiActive || state.connectionState != GeminiConnectionState.Ready) {
            return
        }

        while (queuedCronNotifications.isNotEmpty()) {
            val notification = queuedCronNotifications.removeFirst()
            if (!geminiService.sendTextMessage(notification.text)) {
                queuedCronNotifications.addFirst(notification)
                Log.d(TAG, "Failed to flush queued cron notification; leaving it queued")
                return
            }
        }
    }

    private fun connectGemini(startAuxiliaryServices: Boolean) {
        geminiService.connect { setupOk ->
            if (!setupOk) {
                val msg = when (val state = geminiService.connectionState.value) {
                    is GeminiConnectionState.Error -> state.message
                    else -> "Failed to connect to Gemini"
                }
                stopSession()
                _uiState.value = _uiState.value.copy(errorMessage = msg)
                return@connect
            }

            if (!startAuxiliaryServices) {
                return@connect
            }

            try {
                audioManager.startCapture()
            } catch (e: Exception) {
                stopSession()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Mic capture failed: ${e.message}"
                )
                return@connect
            }

            if (SettingsManager.proactiveNotificationsEnabled) {
                eventClient.connect()
            }
        }
    }

    private fun observeEffectiveResponseMode() {
        viewModelScope.launch {
            combine(
                SettingsManager.responseModeFlow,
                _streamingMode,
                sessionStateManager.pendingConfirmation
            ) { storedMode, streamingMode, pendingConfirmation ->
                deriveEffectiveResponseMode(
                    storedMode = storedMode,
                    streamingMode = streamingMode,
                    pendingConfirmation = pendingConfirmation
                )
            }.collect { mode ->
                val previousMode = _effectiveResponseMode.value
                _effectiveResponseMode.value = mode
                _uiState.update { current ->
                    current.copy(effectiveResponseMode = mode)
                }

                if (
                    previousMode != mode &&
                    _uiState.value.isGeminiActive &&
                    geminiService.connectionState.value == GeminiConnectionState.Ready
                ) {
                    geminiService.restartForModeChange("response_mode_${mode.storageValue}")
                }
            }
        }
    }

    private fun deriveEffectiveResponseMode(
        storedMode: ResponseMode,
        streamingMode: StreamingMode,
        pendingConfirmation: PendingConfirmation?
    ): ResponseMode {
        var mode = storedMode

        if (streamingMode == StreamingMode.GLASSES) {
            mode = mode.tighten()
        }

        if (pendingConfirmation != null) {
            mode = mode.tighten()
        }

        // TODO: Tighten once reconnect retry count is exposed as a stable flow.
        return mode
    }

    private fun buildSystemInstruction(): String {
        val baseInstruction = GeminiConfig.systemInstruction.trim()
        val modeDirective = buildResponseModeDirective(effectiveResponseMode.value)
        val toolIntentBlock =
            "Tool intent hints: send_message is for outbound communication to a person or channel; set_reminder is for creating a time-based reminder or follow-up; capture_task is for saving a task or to-do to handle later."
        val sessionContext = trimSessionContext(sessionStateManager.sessionContextBlock())

        return listOfNotNull(
            baseInstruction.takeIf { it.isNotBlank() },
            modeDirective,
            toolIntentBlock,
            sessionContext.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")
    }

    private fun buildResponseModeDirective(mode: ResponseMode): String = when (mode) {
        ResponseMode.FAST ->
            "Response mode: FAST. Keep spoken replies short, lead with the answer, and prefer a single next step over extra explanation."

        ResponseMode.NORMAL ->
            "Response mode: NORMAL. Stay concise and natural, but include enough context to make the recommendation easy to trust and act on."

        ResponseMode.FOCUSED ->
            "Response mode: FOCUSED. Slow down slightly, state important assumptions, and be explicit about details that could change the outcome of the action."

        ResponseMode.SILENT_ACT ->
            "Response mode: SILENT_ACT. Minimize spoken output, use very short acknowledgments before tool calls, and let the action or result carry most of the interaction."
    }

    private fun trimSessionContext(sessionContext: String): String {
        if (sessionContext.length <= MAX_SESSION_CONTEXT_CHARS) {
            return sessionContext
        }

        return sessionContext
            .take(MAX_SESSION_CONTEXT_CHARS - 3)
            .trimEnd() + "..."
    }

}
