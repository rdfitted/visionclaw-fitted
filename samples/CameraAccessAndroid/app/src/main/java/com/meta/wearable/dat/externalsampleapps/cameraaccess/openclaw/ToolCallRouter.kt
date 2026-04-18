package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorFallbackReason
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope
) {
    private val inFlightJobs = mutableMapOf<String, Job>()
    private var operatorState = OperatorStateMachine.State()
    private val turnCounter = AtomicLong(0)

    fun handleToolCall(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val callId = call.id
        val callName = call.name
        val taskDesc = call.args["task"]?.toString() ?: call.args.toString()
        val context = OperatorContext(
            sessionId = bridge.operatorSessionId,
            turnId = "turn-${turnCounter.incrementAndGet()}",
            toolCallId = callId
        )

        operatorState = OperatorStateMachine.propose(
            state = operatorState,
            context = context,
            toolName = callName,
            task = taskDesc
        )

        if (taskDesc.isBlank()) {
            operatorState = OperatorStateMachine.invalidate(
                operatorState,
                callId,
                "missing_task_payload"
            )
            bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, "Missing task payload"))
            sendResponse(buildToolResponse(callId, callName, ToolResult.Failure("Missing task payload")))
            return
        }

        operatorState = OperatorStateMachine.validate(operatorState, callId)

        if (!SettingsManager.structuredIntentsEnabled) {
            operatorState = OperatorStateMachine.fallback(
                state = operatorState,
                id = callId,
                reason = OperatorFallbackReason.KILL_SWITCH
            )
        } else if (callName != "execute") {
            operatorState = OperatorStateMachine.fallback(
                state = operatorState,
                id = callId,
                reason = OperatorFallbackReason.NO_MATCHING_TOOL
            )
        }

        if (operatorState.circuitBreakerOpen) {
            operatorState = OperatorStateMachine.reject(
                state = operatorState,
                id = callId,
                reason = "circuit_breaker_open"
            )
            val failureCount = operatorState.consecutiveFailures
            val errorResult = ToolResult.Failure(
                "Tool execution is temporarily unavailable after $failureCount consecutive failures. " +
                "Please tell the user you cannot complete this action right now and suggest they check their OpenClaw gateway connection."
            )
            bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, "Circuit breaker open"))
            sendResponse(buildToolResponse(callId, callName, errorResult))
            return
        }

        operatorState = OperatorStateMachine.dispatch(operatorState, callId)
        val job = scope.launch {
            val result = bridge.delegateTask(callId = callId, task = taskDesc, toolName = callName)

            if (!coroutineContext[Job]!!.isCancelled) {
                when (result) {
                    is ToolResult.Success -> {
                        operatorState = OperatorStateMachine.complete(operatorState, callId, result.result)
                    }
                    is ToolResult.Failure -> {
                        operatorState = OperatorStateMachine.fail(operatorState, callId, result.error)
                    }
                }

                val response = buildToolResponse(callId, callName, result)
                sendResponse(response)
            }

            inFlightJobs.remove(callId)
        }

        inFlightJobs[callId] = job
    }

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            inFlightJobs[id]?.let { job ->
                val toolName = operatorState.calls[id]?.toolName ?: id
                job.cancel()
                operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_by_gemini")
                bridge.setToolCallState(id, ToolCallStatus.Cancelled(toolName))
                inFlightJobs.remove(id)
            }
        }
    }

    fun cancelAll() {
        for ((id, job) in inFlightJobs) {
            val toolName = operatorState.calls[id]?.toolName ?: id
            job.cancel()
            operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_during_shutdown")
            bridge.setToolCallState(id, ToolCallStatus.Cancelled(toolName))
        }
        inFlightJobs.clear()
        operatorState = OperatorStateMachine.State()
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
}
