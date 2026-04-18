package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler

class SearchWebHandler : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"] as? String ?: return ToolResult.Failure("Missing query argument")
        return try {
            val response = OpenClawBridge.delegateTask("search: $query")
            ToolResult.Success(response)
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "Unknown error in OpenClawBridge")
        }
    }
}
