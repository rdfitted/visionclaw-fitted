package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult

class GenericExecuteHandler : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        val task = call.args["task"] as? String ?: return ToolResult.Failure("Missing task argument")
        return try {
            val response = OpenClawBridge.delegateTask(task)
            ToolResult.Success(response)
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "Unknown error in OpenClawBridge")
        }
    }
}
