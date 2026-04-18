package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler

sealed interface ToolHandlerResolution {
    data class Available(val handler: ToolHandler) : ToolHandlerResolution
    data object Missing : ToolHandlerResolution
    data class Unavailable(val reason: String? = null) : ToolHandlerResolution
}

class ToolRegistry(
    bridge: OpenClawBridge,
    confirmPendingHandler: ToolHandler? = null,
    disabledTools: Set<String> = emptySet()
) {
    private val handlers = mutableMapOf<String, ToolHandler>()
    private val unavailableTools = disabledTools.toMutableSet()

    init {
        register("execute", GenericExecuteHandler(bridge))
        register("search_web", SearchWebHandler(bridge))
        confirmPendingHandler?.let { register("confirm_pending", it) }
    }

    fun register(name: String, handler: ToolHandler) {
        handlers[name] = handler
    }

    fun markUnavailable(name: String) {
        unavailableTools += name
    }

    fun getHandlerResolution(name: String): ToolHandlerResolution {
        if (name in unavailableTools) {
            return ToolHandlerResolution.Unavailable("Tool '$name' is currently unavailable.")
        }

        val handler = handlers[name] ?: return ToolHandlerResolution.Missing
        return ToolHandlerResolution.Available(handler)
    }
}
