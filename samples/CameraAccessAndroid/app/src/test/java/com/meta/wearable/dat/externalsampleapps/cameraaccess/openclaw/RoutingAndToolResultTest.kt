package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.IntentRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    fun genericExecuteTreatsBlankSearchQueryAsMissingTask() = runBlocking {
        val handler = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.GenericExecuteHandler(
            OpenClawBridge()
        )

        val result = handler.execute(
            GeminiFunctionCall(
                id = "call-blank-search",
                name = "search_web",
                args = mapOf("query" to "   ")
            )
        )

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("Missing task argument", failure.error)
    }

    @Test
    fun toolDeclarationsDoNotExposeUnsupportedInvocationCondition() {
        val searchWeb = ToolDeclarations.allDeclarationsJSON().getJSONObject(1)

        assertFalse(searchWeb.has("invocation_condition"))
        assertEquals("search_web", searchWeb.getString("name"))
    }

    @Test
    fun searchWebHandlerRethrowsCancellation() {
        runBlocking {
            val handler = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers.SearchWebHandler(
                bridge = object : OpenClawBridge() {
                    override suspend fun delegateTask(
                        callId: String,
                        task: String,
                        toolName: String
                    ): ToolResult {
                        throw CancellationException("cancelled")
                    }
                }
            )

            assertFailsWith<CancellationException> {
                handler.execute(
                    GeminiFunctionCall(
                        id = "call-cancel-search",
                        name = "search_web",
                        args = mapOf("query" to "weather")
                    )
                )
            }
        }
    }

    @Test
    fun genericExecuteRethrowsCancellation() {
        runBlocking {
            val handler = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.GenericExecuteHandler(
                bridge = object : OpenClawBridge() {
                    override suspend fun delegateTask(
                        callId: String,
                        task: String,
                        toolName: String
                    ): ToolResult {
                        throw CancellationException("cancelled")
                    }
                }
            )

            assertFailsWith<CancellationException> {
                handler.execute(
                    GeminiFunctionCall(
                        id = "call-cancel-execute",
                        name = "execute",
                        args = mapOf("task" to "Do something")
                    )
                )
            }
        }
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
    fun toolCallRouterMarksThrownRouteAsFailedAndDoesNotLeaveCancelableJob() {
        val bridge = OpenClawBridge()
        val router = ToolCallRouter(
            bridge = bridge,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            intentRouter = IntentRouter(
                bridge = bridge,
                structuredIntentsEnabledProvider = { true },
                toolRegistry = ToolRegistry(bridge).apply {
                    register(
                        "execute",
                        object : ToolHandler {
                            override suspend fun execute(call: GeminiFunctionCall): ToolResult {
                                throw IllegalStateException("Boom")
                            }
                        }
                    )
                }
            )
        )
        var response: org.json.JSONObject? = null

        router.dispatch(
            call = GeminiFunctionCall(
                id = "call-router-failure",
                name = "execute",
                args = mapOf("task" to "Do something")
            ),
            sendResponse = { response = it }
        )

        val functionResponse = response
            ?.getJSONObject("toolResponse")
            ?.getJSONArray("functionResponses")
            ?.getJSONObject(0)
            ?: error("Expected a tool response")
        assertEquals("Boom", functionResponse.getJSONObject("response").getString("error"))

        val failedBeforeCancel = assertIs<ToolCallStatus.Failed>(
            bridge.toolCallStates.value.getValue("call-router-failure").status
        )
        assertEquals("Boom", failedBeforeCancel.error)

        router.cancelToolCalls(listOf("call-router-failure"))

        val failedAfterCancel = assertIs<ToolCallStatus.Failed>(
            bridge.toolCallStates.value.getValue("call-router-failure").status
        )
        assertEquals("Boom", failedAfterCancel.error)
    }

    @Test
    fun failureJsonOmitsHintWhenAbsent() {
        val json = ToolResult.Failure(error = "Boom").toJSON()

        assertEquals("Boom", json.getString("error"))
        assertFalse(json.has("hint"))
    }
}
