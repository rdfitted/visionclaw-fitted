package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler

class SearchWebHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        val query = (call.args["query"] as? String)?.takeIf(String::isNotBlank)
            ?: return ToolResult.Failure(
                error = "Missing query argument",
                hint = "Provide a non-empty query argument."
            )

        return try {
            bridge.delegateTask(
                callId = call.id,
                task = "search: $query",
                toolName = call.name
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                error = e.message ?: "Unknown error in OpenClawBridge",
                hint = "Retry with a specific fact-finding query after the OpenClaw gateway is reachable."
            )
        }
    }
}
