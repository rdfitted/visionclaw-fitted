package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredToolPayloadsTest {
    @Test
    fun sendMessageTaskEscapesTaskLiterals() {
        val task = StructuredToolPayloads.buildSendMessageTask(
            SendMessagePayload(
                recipient = "  Sam \\ Ops \"Lead\"\r\nHQ  ",
                content = "  Path C:\\tmp\r\nSay \"go\"  ",
                channel = MessageChannel.SMS
            )
        )

        assertEquals(
            "Send an SMS to \"Sam \\\\ Ops \\\"Lead\\\" HQ\": \"Path C:\\\\tmp Say \\\"go\\\"\".",
            task
        )
    }

    @Test
    fun sendMessagePromptEscapesAndQuotesRecipient() {
        val prompt = StructuredToolPayloads.buildSendMessagePrompt(
            SendMessagePayload(
                recipient = "  Sam \"Lead\"\r\nHQ  ",
                content = "hello",
                channel = MessageChannel.SMS
            )
        )

        assertEquals(
            "Confirm sending this SMS to \"Sam \\\"Lead\\\" HQ\".",
            prompt
        )
    }

    @Test
    fun reminderTaskAndUndoPayloadEscapeTaskLiterals() {
        val payload = SetReminderPayload(
            whenText = "  tomorrow\r\n7pm  ",
            what = "  review C:\\docs and say \"done\"  "
        )

        assertEquals(
            "Set a reminder for \"tomorrow 7pm\" to \"review C:\\\\docs and say \\\"done\\\"\".",
            StructuredToolPayloads.buildSetReminderTask(payload)
        )
        assertEquals(
            "Cancel the reminder \"review C:\\\\docs and say \\\"done\\\"\" scheduled for \"tomorrow 7pm\".",
            StructuredToolPayloads.buildSetReminderUndoPayload(payload)["task"]
        )
    }

    @Test
    fun captureTaskAndUndoPayloadEscapeTaskLiterals() {
        val payload = CaptureTaskPayload(
            title = "  Ship \"alpha\" build  ",
            priority = TaskPriority.HIGH,
            notes = "  Path C:\\drop\r\nnotify QA  "
        )

        assertEquals(
            "Capture a task titled \"Ship \\\"alpha\\\" build\". Set priority to high. Notes: \"Path C:\\\\drop notify QA\".",
            StructuredToolPayloads.buildCaptureTaskTask(payload)
        )
        assertEquals(
            "Delete the task titled \"Ship \\\"alpha\\\" build\". Notes to match: \"Path C:\\\\drop notify QA\".",
            StructuredToolPayloads.buildCaptureTaskUndoPayload(payload)["task"]
        )
    }
}
