package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult

class GenericExecuteHandler(
    private val bridge: OpenClawBridge
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        val task = extractTask(call) ?: return ToolResult.Failure("Missing task argument")

        return try {
            bridge.delegateTask(
                callId = call.id,
                task = task,
                toolName = call.name
            )
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "Unknown error in OpenClawBridge")
        }
    }

    private fun extractTask(call: GeminiFunctionCall): String? = when (call.name) {
        "execute" -> call.args["task"] as? String
        "search_web" -> (call.args["query"] as? String)?.let { "search: $it" }
        else -> call.args["task"] as? String ?: call.args["query"] as? String
    }
}
