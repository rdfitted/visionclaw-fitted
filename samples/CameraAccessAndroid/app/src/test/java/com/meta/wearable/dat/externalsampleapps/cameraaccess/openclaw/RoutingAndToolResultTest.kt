package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.IntentRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class RoutingAndToolResultTest {
    @Test
    fun failureJsonIncludesHintWhenPresent() {
        val json = ToolResult.Failure(
            error = "Missing query argument",
            hint = "Provide a non-empty query argument."
        ).toJSON()

        assertEquals("Missing query argument", json.getString("error"))
        assertEquals("Provide a non-empty query argument.", json.getString("hint"))
    }

    @Test
    fun searchWebValidationFailureReturnsHint() = runBlocking {
        val handler = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler(
            OpenClawBridge()
        )

        val result = handler.execute(
            GeminiFunctionCall(
                id = "call-1",
                name = "search_web",
                args = emptyMap()
            )
        )

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("Missing query argument", failure.error)
        assertEquals("Provide a non-empty query argument.", failure.hint)
    }

    @Test
    fun genericExecuteValidationFailureReturnsHint() = runBlocking {
        val handler = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.GenericExecuteHandler(
            OpenClawBridge()
        )

        val result = handler.execute(
            GeminiFunctionCall(
                id = "call-1",
                name = "execute",
                args = emptyMap()
            )
        )

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("Missing task argument", failure.error)
        assertEquals(
            "Provide a non-empty task argument describing the action to perform.",
            failure.hint
        )
    }

    @Test
    fun intentRouterEmitsHandlerUnavailableFallbackReason() = runBlocking {
        val bridge = OpenClawBridge()
        val router = IntentRouter(
            bridge = bridge,
            structuredIntentsEnabledProvider = { true },
            genericHandler = object : ToolHandler {
                override suspend fun execute(call: GeminiFunctionCall): ToolResult {
                    return ToolResult.Failure("Fallback failed")
                }
            },
            toolRegistry = ToolRegistry(bridge, disabledTools = setOf("search_web"))
        )

        val result = router.route(
            GeminiFunctionCall(
                id = "call-1",
                name = "search_web",
                args = mapOf("query" to "weather")
            )
        )

        assertEquals("handler_unavailable", result.fallbackReason)
        val failure = assertIs<ToolResult.Failure>(result.result)
        assertEquals(
            "Retry with execute, or re-enable the unavailable structured tool before trying again.",
            failure.hint
        )
    }

    @Test
    fun failureJsonOmitsHintWhenAbsent() {
        val json = ToolResult.Failure(error = "Boom").toJSON()

        assertEquals("Boom", json.getString("error"))
        assertFalse(json.has("hint"))
    }
}
