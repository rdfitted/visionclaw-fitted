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
    bridge: OpenClawBridge
) {
    private val genericHandler = GenericExecuteHandler(bridge)
    private val toolRegistry = ToolRegistry(bridge)

    suspend fun route(call: GeminiFunctionCall): RoutingResult {
        if (!SettingsManager.structuredIntentsEnabled) {
            return RoutingResult(
                result = genericHandler.execute(call),
                fallbackReason = OperatorFallbackReason.KILL_SWITCH
            )
        }

        val handler = toolRegistry.getHandler(call.name)
        if (handler == null) {
            return RoutingResult(
                result = genericHandler.execute(call),
                fallbackReason = OperatorFallbackReason.NO_MATCHING_TOOL
            )
        }

        return RoutingResult(result = handler.execute(call))
    }
}
