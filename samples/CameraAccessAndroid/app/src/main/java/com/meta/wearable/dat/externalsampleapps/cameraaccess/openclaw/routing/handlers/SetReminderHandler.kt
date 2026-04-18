package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.StructuredToolPayloads
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler
import kotlinx.coroutines.CancellationException

class SetReminderHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        StructuredToolPayloads.validateSetReminderArgs(call.args)?.let { issue ->
            return ToolResult.Failure(
                error = issue.error,
                hint = issue.hint
            )
        }

        val payload = StructuredToolPayloads.parseSetReminderPayload(call.args)
            ?: return ToolResult.Failure(
                error = "Invalid set_reminder payload",
                hint = "Provide when and what values for the reminder."
            )

        return try {
            bridge.delegateTask(
                callId = call.id,
                task = StructuredToolPayloads.buildSetReminderTask(payload),
                toolName = call.name
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure(
                error = e.message ?: "Unknown error in OpenClawBridge",
                hint = "Check the OpenClaw gateway connection and retry the reminder."
            )
        }
    }
}
