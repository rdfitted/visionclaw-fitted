package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawNotification
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawNotificationKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.ResponseMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationGateTest {
    private val defaultResponseMode = SettingsManager.responseModeFlow.value

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @After
    fun resetResponseModeFlow() {
        setResponseModeFlow(defaultResponseMode)
    }

    @Test
    fun heartbeatNotificationsAreDroppedWhileConfirmationIsPending() {
        val harness = NotificationGateHarness()

        harness.setPendingConfirmation("pending-heartbeat")

        harness.emitNotification(
            OpenClawNotification(
                kind = OpenClawNotificationKind.HEARTBEAT,
                text = "[Notification from your assistant] Build succeeded"
            )
        )

        assertTrue(harness.fakeWebSocket.sentMessages.isEmpty())
    }

    @Test
    fun cronNotificationsQueueDropOldestAndFlushOnceInOrderAfterPendingClears() {
        val harness = NotificationGateHarness()

        harness.setPendingConfirmation("pending-cron")
        harness.emitNotification(
            OpenClawNotification(
                kind = OpenClawNotificationKind.CRON,
                text = "[Scheduled update] first"
            )
        )
        harness.emitNotification(
            OpenClawNotification(
                kind = OpenClawNotificationKind.CRON,
                text = "[Scheduled update] second"
            )
        )
        harness.emitNotification(
            OpenClawNotification(
                kind = OpenClawNotificationKind.CRON,
                text = "[Scheduled update] third"
            )
        )
        harness.emitNotification(
            OpenClawNotification(
                kind = OpenClawNotificationKind.CRON,
                text = "[Scheduled update] fourth"
            )
        )

        assertTrue(harness.fakeWebSocket.sentMessages.isEmpty())

        harness.clearPendingConfirmation()

        assertContentEquals(
            listOf(
                "[Scheduled update] second",
                "[Scheduled update] third",
                "[Scheduled update] fourth"
            ),
            harness.forwardedTexts()
        )

        harness.syncObservedState()

        assertContentEquals(
            listOf(
                "[Scheduled update] second",
                "[Scheduled update] third",
                "[Scheduled update] fourth"
            ),
            harness.forwardedTexts()
        )
    }

    private inner class NotificationGateHarness {
        val viewModel = GeminiSessionViewModel()
        val fakeWebSocket = FakeWebSocket(sendResult = true)

        private val sessionStateManager = extractSessionStateManager(viewModel)

        init {
            setResponseModeFlow(ResponseMode.SILENT_ACT)
            setUiState(
                viewModel,
                GeminiUiState(
                    isGeminiActive = true,
                    connectionState = GeminiConnectionState.Ready
                )
            )
            setGeminiConnectionState(viewModel, GeminiConnectionState.Ready)
            setGeminiWebSocket(viewModel, fakeWebSocket)
        }

        fun setPendingConfirmation(id: String) {
            sessionStateManager.setPendingConfirmation(
                toolCallId = id,
                toolName = "set_reminder",
                task = "Add a reminder",
                reason = "Needs confirmation"
            )
            syncObservedState()
        }

        fun clearPendingConfirmation() {
            sessionStateManager.clearPendingConfirmation()
            syncObservedState()
        }

        fun syncObservedState() {
            invokePrivateMethod(
                target = viewModel,
                methodName = "applyObservedState",
                parameterTypes = arrayOf(GeminiUiState::class.java),
                args = arrayOf(
                    GeminiUiState(
                        connectionState = GeminiConnectionState.Ready,
                        pendingConfirmation = sessionStateManager.pendingConfirmation.value
                    )
                )
            )
        }

        fun emitNotification(notification: OpenClawNotification) {
            invokePrivateMethod(
                target = viewModel,
                methodName = "handleProactiveNotification",
                parameterTypes = arrayOf(OpenClawNotification::class.java),
                args = arrayOf(notification)
            )
        }

        fun forwardedTexts(): List<String> {
            return fakeWebSocket.sentMessages.map { message ->
                JSONObject(message)
                    .getJSONObject("clientContent")
                    .getJSONArray("turns")
                    .getJSONObject(0)
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSessionStateManager(viewModel: GeminiSessionViewModel): SessionStateManager {
        val field = GeminiSessionViewModel::class.java.getDeclaredField("sessionStateManager")
        field.isAccessible = true
        return field.get(viewModel) as SessionStateManager
    }

    @Suppress("UNCHECKED_CAST")
    private fun setUiState(viewModel: GeminiSessionViewModel, state: GeminiUiState) {
        val field = GeminiSessionViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(viewModel) as MutableStateFlow<GeminiUiState>
        flow.value = state
    }

    @Suppress("UNCHECKED_CAST")
    private fun setResponseModeFlow(mode: ResponseMode) {
        val field = SettingsManager::class.java.getDeclaredField("_responseMode")
        field.isAccessible = true
        val flow = field.get(SettingsManager) as MutableStateFlow<ResponseMode>
        flow.value = mode
    }

    @Suppress("UNCHECKED_CAST")
    private fun setGeminiConnectionState(
        viewModel: GeminiSessionViewModel,
        state: GeminiConnectionState
    ) {
        val geminiServiceField = GeminiSessionViewModel::class.java.getDeclaredField("geminiService")
        geminiServiceField.isAccessible = true
        val geminiService = geminiServiceField.get(viewModel) as GeminiLiveService

        val connectionStateField = GeminiLiveService::class.java.getDeclaredField("_connectionState")
        connectionStateField.isAccessible = true
        val flow = connectionStateField.get(geminiService) as MutableStateFlow<GeminiConnectionState>
        flow.value = state
    }

    private fun setGeminiWebSocket(viewModel: GeminiSessionViewModel, webSocket: WebSocket) {
        val geminiServiceField = GeminiSessionViewModel::class.java.getDeclaredField("geminiService")
        geminiServiceField.isAccessible = true
        val geminiService = geminiServiceField.get(viewModel) as GeminiLiveService

        val webSocketField = GeminiLiveService::class.java.getDeclaredField("webSocket")
        webSocketField.isAccessible = true
        webSocketField.set(geminiService, webSocket)
    }

    private fun invokePrivateMethod(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>
    ) {
        val method = target.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private class FakeWebSocket(
        private val sendResult: Boolean
    ) : WebSocket {
        val sentMessages = mutableListOf<String>()

        override fun request(): Request = Request.Builder().url("https://example.com").build()

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            sentMessages += text
            return sendResult
        }

        override fun send(bytes: ByteString): Boolean = sendResult

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() {
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
