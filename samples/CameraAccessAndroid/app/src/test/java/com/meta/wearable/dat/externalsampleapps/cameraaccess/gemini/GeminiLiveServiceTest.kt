package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString

class GeminiLiveServiceTest {
    @Test
    fun sendTextMessageReturnsFalseWhenWebSocketRejectsSend() {
        val service = GeminiLiveService()
        val webSocket = FakeWebSocket(sendResult = false)

        setConnectionState(service, GeminiConnectionState.Ready)
        setWebSocket(service, webSocket)

        assertFalse(service.sendTextMessage("Reconnect reminder"))
        assertEquals(1, webSocket.sentMessages.size)
        assertEquals(
            "{\"clientContent\":{\"turns\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Reconnect reminder\"}]}]}}",
            webSocket.sentMessages.single()
        )
    }

    @Test
    fun sendTextMessageReturnsFalseWhenSendExecutorTimesOut() {
        val service = TimeoutTestingGeminiLiveService(sendTextTimeoutMs = 25L)
        val webSocket = FakeWebSocket(sendResult = true)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val sendExecutor = sendExecutor(service)

        setConnectionState(service, GeminiConnectionState.Ready)
        setWebSocket(service, webSocket)

        val blocker = sendExecutor.submit {
            blockerStarted.countDown()
            releaseBlocker.await(1, TimeUnit.SECONDS)
        }

        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS))
        assertFalse(service.sendTextMessage("Reconnect reminder"))

        releaseBlocker.countDown()
        blocker.get(1, TimeUnit.SECONDS)
        sendExecutor.submit { }.get(1, TimeUnit.SECONDS)

        assertTrue(webSocket.sentMessages.isEmpty())
    }

    @Test
    fun flushReconnectVoiceNoteKeepsPendingNoteWhenSendFails() {
        val service = RecordingGeminiLiveService(sendResult = false)
        val note = "System note: reconnect and ask for confirmation again."

        setPendingReconnectVoiceNote(service, note)
        flushReconnectVoiceNoteIfNeeded(service)

        assertEquals(listOf(note), service.sentMessages)
        assertEquals(note, pendingReconnectVoiceNote(service))
    }

    @Test
    fun flushReconnectVoiceNoteClearsPendingNoteAfterConfirmedSend() {
        val service = RecordingGeminiLiveService(sendResult = true)
        val note = "System note: reconnect and ask for confirmation again."

        setPendingReconnectVoiceNote(service, note)
        flushReconnectVoiceNoteIfNeeded(service)

        assertEquals(listOf(note), service.sentMessages)
        assertNull(pendingReconnectVoiceNote(service))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setConnectionState(service: GeminiLiveService, state: GeminiConnectionState) {
        val field = GeminiLiveService::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        val flow = field.get(service) as MutableStateFlow<GeminiConnectionState>
        flow.value = state
    }

    private fun setWebSocket(service: GeminiLiveService, webSocket: WebSocket) {
        val field = GeminiLiveService::class.java.getDeclaredField("webSocket")
        field.isAccessible = true
        field.set(service, webSocket)
    }

    private fun sendExecutor(service: GeminiLiveService): ExecutorService {
        val field = GeminiLiveService::class.java.getDeclaredField("sendExecutor")
        field.isAccessible = true
        return field.get(service) as ExecutorService
    }

    private fun setPendingReconnectVoiceNote(service: GeminiLiveService, note: String?) {
        val field = GeminiLiveService::class.java.getDeclaredField("pendingReconnectVoiceNote")
        field.isAccessible = true
        field.set(service, note)
    }

    private fun pendingReconnectVoiceNote(service: GeminiLiveService): String? {
        val field = GeminiLiveService::class.java.getDeclaredField("pendingReconnectVoiceNote")
        field.isAccessible = true
        return field.get(service) as String?
    }

    private fun flushReconnectVoiceNoteIfNeeded(service: GeminiLiveService) {
        val method = GeminiLiveService::class.java.getDeclaredMethod("flushReconnectVoiceNoteIfNeeded")
        method.isAccessible = true
        method.invoke(service)
    }

    private class RecordingGeminiLiveService(
        private val sendResult: Boolean
    ) : GeminiLiveService() {
        val sentMessages = mutableListOf<String>()

        override fun sendTextMessage(text: String): Boolean {
            sentMessages += text
            return sendResult
        }
    }

    private class TimeoutTestingGeminiLiveService(
        override val sendTextTimeoutMs: Long
    ) : GeminiLiveService() {
        override fun logSendTextError(message: String, throwable: Throwable?) {
        }
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
