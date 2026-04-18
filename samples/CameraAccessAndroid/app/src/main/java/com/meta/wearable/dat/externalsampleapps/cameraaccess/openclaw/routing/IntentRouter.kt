package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorFallbackReason
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

data class RoutingResult(
    val result: ToolResult,
    val fallbackReason: String? = null
)

class IntentRouter(
    bridge: OpenClawBridge,
    private val structuredIntentsEnabledProvider: () -> Boolean = { SettingsManager.structuredIntentsEnabled },
    private val genericHandler: ToolHandler = GenericExecuteHandler(bridge),
    private val toolRegistry: ToolRegistry = ToolRegistry(bridge)
) {
    suspend fun route(call: GeminiFunctionCall): RoutingResult {
        if (!structuredIntentsEnabledProvider()) {
            return fallback(call, OperatorFallbackReason.KILL_SWITCH, genericHandler.execute(call))
        }

        return when (val resolution = toolRegistry.getHandlerResolution(call.name)) {
            is ToolHandlerResolution.Available -> RoutingResult(result = resolution.handler.execute(call))
            ToolHandlerResolution.Missing ->
                fallback(call, OperatorFallbackReason.NO_MATCHING_TOOL, genericHandler.execute(call))
            is ToolHandlerResolution.Unavailable ->
                fallback(call, OperatorFallbackReason.HANDLER_UNAVAILABLE, genericHandler.execute(call))
        }
    }

    private fun fallback(
        call: GeminiFunctionCall,
        reason: String,
        result: ToolResult
    ): RoutingResult {
        val resultWithHint = when (result) {
            is ToolResult.Failure -> {
                if (result.hint != null) {
                    result
                } else {
                    result.copy(hint = fallbackHint(call, reason))
                }
            }
            is ToolResult.Success -> result
        }

        return RoutingResult(
            result = resultWithHint,
            fallbackReason = reason
        )
    }

    private fun fallbackHint(call: GeminiFunctionCall, reason: String): String = when (reason) {
        OperatorFallbackReason.KILL_SWITCH -> when (call.name) {
            "search_web" -> "Structured routing is disabled. Use execute for this lookup or re-enable structured intents."
            else -> "Structured routing is disabled. Use execute until structured intents are re-enabled."
        }
        OperatorFallbackReason.NO_MATCHING_TOOL ->
            "Use execute for actions and search_web for fact lookups."
        OperatorFallbackReason.HANDLER_UNAVAILABLE ->
            "Retry with execute, or re-enable the unavailable structured tool before trying again."
        else -> "Retry with a supported tool for this request."
    }
}
