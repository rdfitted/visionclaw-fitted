package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.IntentRouter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope,
    private val intentRouter: IntentRouter = IntentRouter(bridge)
) {
    private val stateLock = Any()
    private val inFlightJobs = mutableMapOf<String, Job>()
    private var operatorState = OperatorStateMachine.State()
    private val turnCounter = AtomicLong(0)

    fun dispatch(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val callId = call.id
        val callName = call.name
        val taskDesc = extractTaskDesc(call)
        val context = OperatorContext(
            sessionId = bridge.operatorSessionId,
            turnId = "turn-${turnCounter.incrementAndGet()}",
            toolCallId = callId
        )

        if (taskDesc.isBlank()) {
            synchronized(stateLock) {
                operatorState = OperatorStateMachine.invalidateNew(
                    state = operatorState,
                    context = context,
                    toolName = callName,
                    reason = "missing_task_payload"
                )
            }
            bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, "Missing task payload"))
            sendResponse(
                buildToolResponse(
                    callId,
                    callName,
                    ToolResult.Failure(
                        error = "Missing task payload",
                        hint = "Provide a non-empty task argument describing the action to perform."
                    )
                )
            )
            return
        }

        val circuitBreakerFailure = synchronized(stateLock) {
            operatorState = OperatorStateMachine.propose(
                state = operatorState,
                context = context,
                toolName = callName,
                task = taskDesc
            )
            operatorState = OperatorStateMachine.validate(operatorState, callId)

            if (operatorState.circuitBreakerOpen) {
                operatorState = OperatorStateMachine.reject(
                    state = operatorState,
                    id = callId,
                    reason = "circuit_breaker_open"
                )
                val failureCount = operatorState.consecutiveFailures
                ToolResult.Failure(
                    error = "Tool execution is temporarily unavailable after $failureCount consecutive failures. " +
                        "Please tell the user you cannot complete this action right now and suggest they check their OpenClaw gateway connection.",
                    hint = "Check the OpenClaw gateway connection, wait for failures to stop, then retry the action."
                )
            } else {
                operatorState = OperatorStateMachine.dispatch(operatorState, callId)
                null
            }
        }

        if (circuitBreakerFailure != null) {
            bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, "Circuit breaker open"))
            sendResponse(buildToolResponse(callId, callName, circuitBreakerFailure))
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var response: JSONObject? = null
            try {
                val routingResult = intentRouter.route(call)
                val result = routingResult.result

                if (!coroutineContext[Job]!!.isCancelled) {
                    synchronized(stateLock) {
                        routingResult.fallbackReason?.let { reason ->
                            operatorState = OperatorStateMachine.fallback(
                                state = operatorState,
                                id = callId,
                                reason = reason
                            )
                        }

                        when (result) {
                            is ToolResult.Success -> {
                                operatorState = OperatorStateMachine.complete(operatorState, callId, result.result)
                            }
                            is ToolResult.Failure -> {
                                operatorState = OperatorStateMachine.fail(operatorState, callId, result.error)
                                bridge.setToolCallState(
                                    callId,
                                    ToolCallStatus.Failed(callName, result.error)
                                )
                            }
                        }
                    }

                    response = buildToolResponse(callId, callName, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.message ?: "Unhandled routing failure"
                synchronized(stateLock) {
                    operatorState = OperatorStateMachine.fail(operatorState, callId, error)
                }
                bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, error))
                response = buildToolResponse(callId, callName, ToolResult.Failure(error))
            } finally {
                synchronized(stateLock) {
                    inFlightJobs.remove(callId)
                }
            }

            response?.let(sendResponse)
        }

        synchronized(stateLock) {
            inFlightJobs[callId] = job
        }
        job.start()
    }

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            val cancelled = synchronized(stateLock) {
                val job = inFlightJobs.remove(id) ?: return@synchronized null
                val toolName = operatorState.calls[id]?.toolName ?: id
                operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_by_gemini")
                job to toolName
            }
            cancelled?.let { (job, toolName) ->
                job.cancel()
                bridge.setToolCallState(id, ToolCallStatus.Cancelled(toolName))
            }
        }
    }

    fun cancelAll() {
        val cancelled = synchronized(stateLock) {
            val jobs = inFlightJobs.map { (id, job) ->
                val toolName = operatorState.calls[id]?.toolName ?: id
                operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_during_shutdown")
                Triple(id, job, toolName)
            }
            inFlightJobs.clear()
            operatorState = OperatorStateMachine.State()
            jobs
        }

        for ((id, job, toolName) in cancelled) {
            job.cancel()
            bridge.setToolCallState(id, ToolCallStatus.Cancelled(toolName))
        }
    }

    private fun buildToolResponse(
        callId: String,
        name: String,
        result: ToolResult
    ): JSONObject {
        return JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().put(JSONObject().apply {
                    put("id", callId)
                    put("name", name)
                    put("response", result.toJSON())
                }))
            })
        }
    }

    private fun extractTaskDesc(call: GeminiFunctionCall): String {
        return (call.args["task"] as? String)?.takeIf(String::isNotBlank)
            ?: (call.args["query"] as? String)?.takeIf(String::isNotBlank)
            ?: ""
    }
}
