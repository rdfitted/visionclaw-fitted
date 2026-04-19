package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.CaptureTaskHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SendMessageHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SetReminderHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager

sealed interface ToolHandlerResolution {
    data class Available(val handler: ToolHandler) : ToolHandlerResolution
    data object Missing : ToolHandlerResolution
    data class Unavailable(val reason: String? = null) : ToolHandlerResolution
}

class ToolRegistry(
    bridge: OpenClawBridge,
    sessionStateManager: SessionStateManager? = null,
    confirmPendingHandler: ToolHandler? = null,
    disabledTools: Set<String> = emptySet()
) {
    private val handlers = mutableMapOf<String, ToolHandler>()
    private val unavailableTools = disabledTools.toMutableSet()

    init {
        register("execute", GenericExecuteHandler(bridge))
        register("search_web", SearchWebHandler(bridge))
        if (sessionStateManager != null) {
            register("send_message", SendMessageHandler(bridge))
        }
        register("set_reminder", SetReminderHandler(bridge))
        register("capture_task", CaptureTaskHandler(bridge))
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
