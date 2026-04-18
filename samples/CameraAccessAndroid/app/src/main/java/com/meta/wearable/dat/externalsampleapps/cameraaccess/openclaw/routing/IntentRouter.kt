package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object IntentRouter {
    private val genericHandler = GenericExecuteHandler()

    suspend fun route(call: GeminiFunctionCall): ToolResult {
        if (!SettingsManager.structuredIntentsEnabled) {
            // Kill-switch off: short-circuit everything to GenericExecuteHandler
            // We map search_web query to task if needed, but the objective says:
            // "honors structuredIntentsEnabled kill-switch (when off, short-circuit to GenericExecuteHandler)"
            
            // If it's execute, just pass it through. 
            // If it's search_web, we can either reject or wrap it. 
            // Given the instruction "short-circuit to GenericExecuteHandler", 
            // I will try to extract 'task' or 'query' and treat it as a generic task.
            
            val task = when (call.name) {
                "execute" -> call.args["task"] as? String
                "search_web" -> "search: ${call.args["query"]}"
                else -> call.args["task"] as? String ?: call.args["query"] as? String
            } ?: "Manual task execution for ${call.name}"
            
            return genericHandler.execute(GeminiFunctionCall(call.id, "execute", mapOf("task" to task)))
        }

        val handler = ToolRegistry.getHandler(call.name) ?: genericHandler
        return handler.execute(call)
    }
}
