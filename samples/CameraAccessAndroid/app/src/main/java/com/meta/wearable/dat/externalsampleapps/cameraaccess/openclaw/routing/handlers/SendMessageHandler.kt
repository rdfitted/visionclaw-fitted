package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.StructuredToolPayloads
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import kotlinx.coroutines.CancellationException

class SendMessageHandler(
    private val bridge: OpenClawBridge,
    private val sessionStateManager: SessionStateManager
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        StructuredToolPayloads.validateSendMessageArgs(call.args)?.let { issue ->
            return ToolResult.Failure(
                error = issue.error,
                hint = issue.hint
            )
        }

        val payload = StructuredToolPayloads.parseSendMessagePayload(call.args)
            ?: return ToolResult.Failure(
                error = "Invalid send_message payload",
                hint = "Provide recipient, content, and an optional channel of sms, chat, or email."
            )

        StructuredToolPayloads.assessSendMessage(payload, sessionStateManager)

        return try {
            bridge.delegateTask(
                callId = call.id,
                task = StructuredToolPayloads.buildSendMessageTask(payload),
                toolName = call.name
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure(
                error = e.message ?: "Unknown error in OpenClawBridge",
                hint = "Check the OpenClaw gateway connection and retry the message."
            )
        }
    }
}
