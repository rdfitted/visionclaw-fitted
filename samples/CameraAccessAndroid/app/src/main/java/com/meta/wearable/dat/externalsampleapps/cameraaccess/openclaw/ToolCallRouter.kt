package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ToolCallRouter"
    }

    private val inFlightJobs = mutableMapOf<String, Job>()
    private val _toolCallStates = MutableStateFlow<Map<String, ToolCallState>>(emptyMap())
    val toolCallStates: StateFlow<Map<String, ToolCallState>> = _toolCallStates.asStateFlow()
    private var operatorState = OperatorStateMachine.State()

    fun handleToolCall(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val callId = call.id
        val callName = call.name
        val taskDesc = call.args["task"]?.toString() ?: call.args.toString()

        Log.d(TAG, "Received: $callName (id: $callId) args: ${call.args}")
        operatorState = OperatorStateMachine.propose(operatorState, callId, callName, taskDesc)

        if (taskDesc.isBlank()) {
            operatorState = OperatorStateMachine.invalidate(operatorState, callId, "Missing task payload")
            setToolCallState(callId, ToolCallStatus.Failed(callName, "Missing task payload"))
            sendResponse(buildToolResponse(callId, callName, ToolResult.Failure("Missing task payload")))
            return
        }

        operatorState = OperatorStateMachine.validate(operatorState, callId)

        if (operatorState.circuitBreakerOpen) {
            operatorState = OperatorStateMachine.reject(
                state = operatorState,
                id = callId,
                reason = "Circuit breaker open"
            )
            val failureCount = operatorState.consecutiveFailures
            Log.d(TAG, "Circuit breaker open ($failureCount consecutive failures), rejecting $callId")
            val errorResult = ToolResult.Failure(
                "Tool execution is temporarily unavailable after $failureCount consecutive failures. " +
                "Please tell the user you cannot complete this action right now and suggest they check their OpenClaw gateway connection."
            )
            setToolCallState(callId, ToolCallStatus.Failed(callName, "Circuit breaker open"))
            sendResponse(buildToolResponse(callId, callName, errorResult))
            return
        }

        operatorState = OperatorStateMachine.dispatch(operatorState, callId)
        val job = scope.launch {
            val result = bridge.delegateTask(callId = callId, task = taskDesc, toolName = callName)

            if (!coroutineContext[Job]!!.isCancelled) {
                Log.d(TAG, "Result for $callName (id: $callId): $result")

                when (result) {
                    is ToolResult.Success -> {
                        operatorState = OperatorStateMachine.complete(operatorState, callId, result.result)
                        setToolCallState(callId, ToolCallStatus.Completed(callName))
                    }
                    is ToolResult.Failure -> {
                        operatorState = OperatorStateMachine.fail(operatorState, callId, result.error)
                        setToolCallState(callId, ToolCallStatus.Failed(callName, result.error))
                    }
                }

                val response = buildToolResponse(callId, callName, result)
                sendResponse(response)
            } else {
                Log.d(TAG, "Task $callId was cancelled, skipping response")
            }

            inFlightJobs.remove(callId)
        }

        inFlightJobs[callId] = job
    }

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            inFlightJobs[id]?.let { job ->
                Log.d(TAG, "Cancelling in-flight call: $id")
                job.cancel()
                operatorState = OperatorStateMachine.cancel(operatorState, id, "Cancelled by Gemini")
                setToolCallState(id, ToolCallStatus.Cancelled(id))
                bridge.setToolCallState(id, ToolCallStatus.Cancelled(id))
                inFlightJobs.remove(id)
            }
        }
    }

    fun cancelAll() {
        for ((id, job) in inFlightJobs) {
            Log.d(TAG, "Cancelling in-flight call: $id")
            job.cancel()
            operatorState = OperatorStateMachine.cancel(operatorState, id, "Cancelled during shutdown")
            setToolCallState(id, ToolCallStatus.Cancelled(id))
            bridge.setToolCallState(id, ToolCallStatus.Cancelled(id))
        }
        inFlightJobs.clear()
        operatorState = OperatorStateMachine.State()
        _toolCallStates.value = emptyMap()
    }

    private fun setToolCallState(callId: String, status: ToolCallStatus) {
        _toolCallStates.update { current ->
            current + (callId to ToolCallState(status = status))
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
}
