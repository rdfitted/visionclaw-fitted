package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ConfirmationPolicy
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
    private val sessionStateManager: SessionStateManager,
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
        sessionStateManager.observeText(taskDesc)
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
            sessionStateManager.recordToolResult(
                toolName = callName,
                result = ToolResult.Failure("Missing task payload")
            )
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

        val dispatchDecision = synchronized(stateLock) {
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
                DispatchDecision.Rejected(
                    ToolResult.Failure(
                        error = "Tool execution is temporarily unavailable after $failureCount consecutive failures. " +
                            "Please tell the user you cannot complete this action right now and suggest they check their OpenClaw gateway connection.",
                        hint = "Check the OpenClaw gateway connection, wait for failures to stop, then retry the action."
                    )
                )
            } else {
                when (val tier = ConfirmationPolicy.evaluate(call, sessionStateManager)) {
                    is ConfirmationPolicy.Tier.AlwaysConfirm -> {
                        val prompt = "Confirm before taking this action."
                        operatorState = OperatorStateMachine.awaitConfirmation(operatorState, callId, prompt)
                        sessionStateManager.setPendingConfirmation(callId, callName, taskDesc, prompt)
                        DispatchDecision.AwaitingConfirmation(
                            ToolResult.Success(
                                "Confirmation required. Pending action id: $callId. Ask the user to approve this action, then call confirm_pending with pendingActionId=\"$callId\" and confirm=true. If they decline, call confirm_pending with pendingActionId=\"$callId\" and confirm=false."
                            )
                        )
                    }

                    is ConfirmationPolicy.Tier.ConditionalConfirm -> {
                        operatorState = OperatorStateMachine.awaitConfirmation(operatorState, callId, tier.prompt)
                        sessionStateManager.setPendingConfirmation(callId, callName, taskDesc, tier.prompt)
                        DispatchDecision.AwaitingConfirmation(
                            ToolResult.Success(
                                "Confirmation required. Pending action id: $callId. ${tier.prompt} " +
                                    "Ask the user to approve this action, then call confirm_pending with " +
                                    "pendingActionId=\"$callId\" and confirm=true. If they decline, call " +
                                    "confirm_pending with pendingActionId=\"$callId\" and confirm=false."
                            )
                        )
                    }

                    ConfirmationPolicy.Tier.Implicit -> {
                        operatorState = OperatorStateMachine.dispatch(operatorState, callId)
                        DispatchDecision.Dispatch
                    }
                }
            }
        }

        when (dispatchDecision) {
            is DispatchDecision.Rejected -> {
                bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, "Circuit breaker open"))
                sessionStateManager.recordToolResult(callName, dispatchDecision.result)
                sendResponse(buildToolResponse(callId, callName, dispatchDecision.result))
                return
            }

            is DispatchDecision.AwaitingConfirmation -> {
                sendResponse(buildToolResponse(callId, callName, dispatchDecision.result))
                return
            }

            DispatchDecision.Dispatch -> Unit
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

                    sessionStateManager.recordToolResult(callName, result)
                    response = buildToolResponse(callId, callName, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.message ?: "Unhandled routing failure"
                val failure = ToolResult.Failure(error)
                synchronized(stateLock) {
                    operatorState = OperatorStateMachine.fail(operatorState, callId, error)
                }
                bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, error))
                sessionStateManager.recordToolResult(callName, failure)
                response = buildToolResponse(callId, callName, failure)
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
            val awaiting = operatorState.calls.values
                .filterIsInstance<OperatorStateMachine.OperatorState.AwaitingConfirmation>()
                .map { awaiting ->
                    operatorState = OperatorStateMachine.cancel(
                        operatorState,
                        awaiting.id,
                        "cancelled_during_shutdown"
                    )
                    Triple(awaiting.id, null, awaiting.toolName)
                }
            inFlightJobs.clear()
            sessionStateManager.clearPendingConfirmation()
            operatorState = OperatorStateMachine.State()
            jobs + awaiting
        }

        for ((id, job, toolName) in cancelled) {
            job?.cancel()
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

    private sealed interface DispatchDecision {
        data class Rejected(val result: ToolResult.Failure) : DispatchDecision
        data class AwaitingConfirmation(val result: ToolResult.Success) : DispatchDecision
        data object Dispatch : DispatchDecision
    }
}
