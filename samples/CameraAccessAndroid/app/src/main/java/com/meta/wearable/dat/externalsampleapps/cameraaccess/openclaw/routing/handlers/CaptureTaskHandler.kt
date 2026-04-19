package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.StructuredToolPayloads
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler

class CaptureTaskHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        return bridge.executeStructuredHandler(
            call = call,
            validateArgs = StructuredToolPayloads::validateCaptureTaskArgs,
            parsePayload = StructuredToolPayloads::parseCaptureTaskPayload,
            invalidPayloadError = "Invalid capture_task payload",
            invalidPayloadHint = "Provide a title and optional priority of low, med, or high.",
            bridgeFailureHint = "Check the OpenClaw gateway connection and retry the task capture.",
            buildTask = StructuredToolPayloads::buildCaptureTaskTask
        )
    }
}
