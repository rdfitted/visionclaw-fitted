package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.StructuredToolPayloads
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler

class SetReminderHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        return bridge.executeStructuredHandler(
            call = call,
            validateArgs = StructuredToolPayloads::validateSetReminderArgs,
            parsePayload = StructuredToolPayloads::parseSetReminderPayload,
            invalidPayloadError = "Invalid set_reminder payload",
            invalidPayloadHint = "Provide when and what values for the reminder.",
            bridgeFailureHint = "Check the OpenClaw gateway connection and retry the reminder.",
            buildTask = StructuredToolPayloads::buildSetReminderTask
        )
    }
}
