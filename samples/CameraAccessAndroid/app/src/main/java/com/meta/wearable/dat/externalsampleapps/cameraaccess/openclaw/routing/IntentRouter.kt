package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorFallbackReason
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

data class RoutingResult(
    val result: ToolResult,
    val fallbackReason: String? = null
)

data class IntentDispatchPlan(
    val handler: ToolHandler,
    val dispatchedCall: GeminiFunctionCall,
    val fallbackReason: String? = null,
    val confirmationTier: ConfirmationPolicy.Tier = ConfirmationPolicy.Tier.Implicit
)

class IntentRouter(
    bridge: OpenClawBridge,
    private val sessionStateManager: SessionStateManager,
    private val structuredIntentsEnabledProvider: () -> Boolean = { SettingsManager.structuredIntentsEnabledFlow.value },
    private val genericHandler: ToolHandler = GenericExecuteHandler(bridge),
    private val toolRegistry: ToolRegistry = ToolRegistry(bridge, sessionStateManager = sessionStateManager)
) {
    fun resolve(call: GeminiFunctionCall): IntentDispatchPlan {
        if (call.name != "confirm_pending" && !structuredIntentsEnabledProvider()) {
            return plan(
                call = call,
                handler = genericHandler,
                fallbackReason = OperatorFallbackReason.KILL_SWITCH
            )
        }

        return when (val resolution = toolRegistry.getHandlerResolution(call.name)) {
            is ToolHandlerResolution.Available -> plan(call, resolution.handler)
            ToolHandlerResolution.Missing ->
                plan(
                    call = call,
                    handler = genericHandler,
                    fallbackReason = OperatorFallbackReason.NO_MATCHING_TOOL
                )
            is ToolHandlerResolution.Unavailable ->
                plan(
                    call = call,
                    handler = genericHandler,
                    fallbackReason = OperatorFallbackReason.HANDLER_UNAVAILABLE
                )
        }
    }

    suspend fun route(call: GeminiFunctionCall): RoutingResult {
        return execute(call, resolve(call))
    }

    suspend fun execute(call: GeminiFunctionCall, plan: IntentDispatchPlan): RoutingResult {
        return fallback(call, plan.fallbackReason, plan.handler.execute(plan.dispatchedCall))
    }

    private fun fallback(
        call: GeminiFunctionCall,
        reason: String?,
        result: ToolResult
    ): RoutingResult {
        if (reason == null) {
            return RoutingResult(result = result)
        }

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

    private fun plan(
        call: GeminiFunctionCall,
        handler: ToolHandler,
        fallbackReason: String? = null
    ): IntentDispatchPlan {
        val dispatchedCall = when (fallbackReason) {
            OperatorFallbackReason.KILL_SWITCH,
            OperatorFallbackReason.NO_MATCHING_TOOL,
            OperatorFallbackReason.HANDLER_UNAVAILABLE ->
                StructuredToolPayloads.buildFallbackCall(call) ?: call
            else -> call
        }

        return IntentDispatchPlan(
            handler = handler,
            dispatchedCall = dispatchedCall,
            fallbackReason = fallbackReason,
            confirmationTier = ConfirmationPolicy.evaluate(call, sessionStateManager)
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
        OperatorFallbackReason.CONFIRMATION_REQUIRED ->
            "Ask the user to confirm the pending action, then call confirm_pending with the pendingActionId."
        else -> "Retry with a supported tool for this request."
    }
}
