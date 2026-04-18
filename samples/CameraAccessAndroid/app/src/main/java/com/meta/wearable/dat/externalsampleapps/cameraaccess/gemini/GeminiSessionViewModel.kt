package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.PendingConfirmation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
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
)

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val openClawBridge = OpenClawBridge()
    private val sessionStateManager = SessionStateManager(openClawBridge)
    private val geminiService = object : GeminiLiveService() {
        override fun buildSystemInstruction(): String {
            val sessionContext = this@GeminiSessionViewModel.sessionStateManager.sessionContextBlock()
            val baseInstruction = GeminiConfig.systemInstruction.trim()
            return when {
                sessionContext.isBlank() -> baseInstruction
                baseInstruction.isBlank() -> sessionContext
                else -> "$baseInstruction\n\n$sessionContext"
            }
        }
    }.apply {
        this.sessionStateManager = this@GeminiSessionViewModel.sessionStateManager
    }
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private var lastVideoFrameTime: Long = 0
    private var stateObservationJob: Job? = null
    private var toolCallRouter: ToolCallRouter? = null

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    fun startSession() {
        if (_uiState.value.isGeminiActive) return

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        sessionStateManager.reset(openClawBridge.operatorSessionId)
        _uiState.value = _uiState.value.copy(isGeminiActive = true)

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
                launch {
                    combine(
                        openClawBridge.lastToolCallStatus,
                        openClawBridge.connectionState,
                        sessionStateManager.pendingConfirmation
                    ) { toolCallStatus, openClawConnectionState, pendingConfirmation ->
                        Triple(toolCallStatus, openClawConnectionState, pendingConfirmation)
                    }.collect { (toolCallStatus, openClawConnectionState, pendingConfirmation) ->
                        _uiState.update { current ->
                            current.copy(
                                connectionState = geminiService.connectionState.value,
                                isModelSpeaking = geminiService.isModelSpeaking.value,
                                toolCallStatus = toolCallStatus,
                                pendingConfirmation = pendingConfirmation,
                                openClawConnectionState = openClawConnectionState,
                            )
                        }
                    }
                }

                launch {
                    geminiService.connectionState.collect { connectionState ->
                        _uiState.update { current -> current.copy(connectionState = connectionState) }
                    }
                }

                launch {
                    geminiService.isModelSpeaking.collect { isModelSpeaking ->
                        _uiState.update { current -> current.copy(isModelSpeaking = isModelSpeaking) }
                    }
                }

                launch {
                    openClawBridge.lastToolCallStatus.collect { toolCallStatus ->
                        _uiState.update { current -> current.copy(toolCallStatus = toolCallStatus) }
                    }
                }

                launch {
                    openClawBridge.connectionState.collect { openClawConnectionState ->
                        _uiState.update { current ->
                            current.copy(openClawConnectionState = openClawConnectionState)
                        }
                    }
                }
            }

            // Connect to Gemini
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

                // Start mic capture
                try {
                    audioManager.startCapture()
                } catch (e: Exception) {
                    stopSession()
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mic capture failed: ${e.message}"
                    )
                }

                // Connect to OpenClaw event stream for proactive notifications
                if (SettingsManager.proactiveNotificationsEnabled) {
                    eventClient.onNotification = { text ->
                        val state = _uiState.value
                        if (state.isGeminiActive && state.connectionState == GeminiConnectionState.Ready) {
                            geminiService.sendTextMessage(text)
                        }
                    }
                    eventClient.connect()
                }
            }
        }
    }

    fun stopSession() {
        eventClient.disconnect()
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
        openClawBridge.shutdown()
        super.onCleared()
    }
}
