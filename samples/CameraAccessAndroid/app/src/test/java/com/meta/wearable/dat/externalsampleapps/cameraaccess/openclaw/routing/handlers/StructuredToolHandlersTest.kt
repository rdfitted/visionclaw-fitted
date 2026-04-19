package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class StructuredToolHandlersTest {
    @Test
    fun sendMessageHandlerRejectsMissingRecipient() = runBlocking {
        val bridge = RecordingBridge()
        val handler = SendMessageHandler(bridge)

        val result = handler.execute(
            GeminiFunctionCall(
                id = "send-missing-recipient",
                name = "send_message",
                args = mapOf("content" to "On my way")
            )
        )

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("Missing recipient argument", failure.error)
        assertEquals("Provide a non-empty recipient.", failure.hint)
    }

    @Test
    fun sendMessageHandlerDelegatesStructuredTask() = runBlocking {
        val bridge = RecordingBridge()
        val handler = SendMessageHandler(bridge)

        val result = handler.execute(
            GeminiFunctionCall(
                id = "send-fast-path",
                name = "send_message",
                args = mapOf(
                    "recipient" to "Sam Carter",
                    "content" to "On my way",
                    "channel" to "sms"
                )
            )
        )

        assertEquals("Send an SMS to \"Sam Carter\": \"On my way\".", bridge.lastTask)
        assertEquals("send_message", bridge.lastToolName)
        assertEquals("ok", assertIs<ToolResult.Success>(result).result)
    }

    @Test
    fun setReminderHandlerRejectsMissingWhat() = runBlocking {
        val bridge = RecordingBridge()
        val handler = SetReminderHandler(bridge)

        val result = handler.execute(
            GeminiFunctionCall(
                id = "reminder-missing-what",
                name = "set_reminder",
                args = mapOf("when" to "tomorrow at 3pm")
            )
        )

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("Missing what argument", failure.error)
        assertEquals("Provide what the reminder should be about.", failure.hint)
    }

    @Test
    fun captureTaskHandlerDelegatesStructuredTask() = runBlocking {
        val bridge = RecordingBridge()
        val handler = CaptureTaskHandler(bridge)

        val result = handler.execute(
            GeminiFunctionCall(
                id = "capture-task",
                name = "capture_task",
                args = mapOf(
                    "title" to "File taxes",
                    "priority" to "high",
                    "notes" to "Pull the 1099s before Monday"
                )
            )
        )

        assertEquals(
            "Capture a task titled \"File taxes\". Set priority to high. Notes: \"Pull the 1099s before Monday\".",
            bridge.lastTask
        )
        assertEquals("capture_task", bridge.lastToolName)
        assertEquals("ok", assertIs<ToolResult.Success>(result).result)
    }

    private class RecordingBridge : OpenClawBridge() {
        var lastTask: String? = null
        var lastToolName: String? = null

        override suspend fun delegateTask(
            callId: String,
            task: String,
            toolName: String
        ): ToolResult {
            lastTask = task
            lastToolName = toolName
            return ToolResult.Success("ok")
        }

        override fun resetSession() = Unit
    }
}
