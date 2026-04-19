package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.StructuredToolPayloads
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler

class SendMessageHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        return bridge.executeStructuredHandler(
            call = call,
            validateArgs = StructuredToolPayloads::validateSendMessageArgs,
            parsePayload = StructuredToolPayloads::parseSendMessagePayload,
            invalidPayloadError = "Invalid send_message payload",
            invalidPayloadHint = "Provide recipient, content, and an optional channel of sms, chat, or email.",
            bridgeFailureHint = "Check the OpenClaw gateway connection and retry the message.",
            buildTask = StructuredToolPayloads::buildSendMessageTask
        )
    }
}
