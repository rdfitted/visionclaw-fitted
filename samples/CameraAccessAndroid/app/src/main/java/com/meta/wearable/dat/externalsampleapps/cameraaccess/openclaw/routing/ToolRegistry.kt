package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler

class ToolRegistry(
    bridge: OpenClawBridge
) {
    private val handlers = mutableMapOf<String, ToolHandler>()

    init {
        register("execute", GenericExecuteHandler(bridge))
        register("search_web", SearchWebHandler(bridge))
    }

    fun register(name: String, handler: ToolHandler) {
        handlers[name] = handler
    }

    fun getHandler(name: String): ToolHandler? = handlers[name]
}
