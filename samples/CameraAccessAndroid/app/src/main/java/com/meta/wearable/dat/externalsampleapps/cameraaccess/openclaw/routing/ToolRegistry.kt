package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler

object ToolRegistry {
    private val handlers = mutableMapOf<String, ToolHandler>()

    init {
        register("execute", GenericExecuteHandler())
        register("search_web", SearchWebHandler())
    }

    fun register(name: String, handler: ToolHandler) {
        handlers[name] = handler
    }

    fun getHandler(name: String): ToolHandler? = handlers[name]
}
