package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorFallbackReason
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ConfirmPendingHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ConfirmationPolicy
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.IntentDispatchPlan
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.IntentRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.PendingConfirmationController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ToolRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope,
    private val sessionStateManager: SessionStateManager,
    intentRouterOverride: IntentRouter? = null
) {
    companion object {
        private const val PENDING_CONFIRMATION_TIMEOUT_MS = 30_000L
    }

    private val stateLock = Any()
    private val inFlightJobs = mutableMapOf<String, Job>()
    private val pendingActions = mutableMapOf<String, PendingAction>()
    private val pendingTimeouts = mutableMapOf<String, Job>()
    private var operatorState = OperatorStateMachine.State()
    private val turnCounter = AtomicLong(0)

    private val pendingConfirmationController = object : PendingConfirmationController {
        override suspend fun confirmPending(pendingActionId: String): ToolResult {
            val pendingAction = synchronized(stateLock) {
                val pending = sessionStateManager.pendingConfirmation.value
                val storedAction = pendingActions[pendingActionId]
                if (pending == null || pending.id != pendingActionId || storedAction == null) {
                    return@synchronized null
                }

                pendingTimeouts.remove(pendingActionId)?.cancel()
                pendingActions.remove(pendingActionId)
                sessionStateManager.clearPendingConfirmation()
                operatorState = OperatorStateMachine.confirm(operatorState, pendingActionId)
                operatorState = OperatorStateMachine.dispatch(operatorState, pendingActionId)
                storedAction
            } ?: return ToolResult.Success("No pending action matched that confirmation request.")

            return executeResolvedIntent(
                callId = pendingAction.call.id,
                callName = pendingAction.call.name,
                call = pendingAction.call,
                intentPlan = pendingAction.intentPlan
            )
        }

        override suspend fun cancelPending(pendingActionId: String, reason: String): ToolResult {
            val cancelled = synchronized(stateLock) {
                cancelPendingActionLocked(pendingActionId, reason)
            } ?: return ToolResult.Success("No pending action matched that confirmation request.")

            bridge.setToolCallState(cancelled.call.id, ToolCallStatus.Cancelled(cancelled.call.name))
            return ToolResult.Success("Cancelled pending action $pendingActionId.")
        }
    }

    private val intentRouter = intentRouterOverride ?: IntentRouter(
        bridge = bridge,
        sessionStateManager = sessionStateManager,
        toolRegistry = ToolRegistry(
            bridge = bridge,
            confirmPendingHandler = ConfirmPendingHandler(
                sessionStateManager = sessionStateManager,
                controller = pendingConfirmationController
            )
        )
    )

    fun dispatch(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val invalidated = if (call.name != "confirm_pending") {
            synchronized(stateLock) {
                invalidatePendingActionLocked("superseded_by_new_proposal")
            }
        } else {
            null
        }
        invalidated?.let { pendingAction ->
            bridge.setToolCallState(pendingAction.call.id, ToolCallStatus.Cancelled(pendingAction.call.name))
        }

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
            val intentPlan = intentRouter.resolve(call)

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
                when (val tier = intentPlan.confirmationTier) {
                    is ConfirmationPolicy.Tier.AlwaysConfirm -> {
                        val prompt = "Confirm before taking this action."
                        operatorState = OperatorStateMachine.awaitConfirmation(operatorState, callId, prompt)
                        operatorState = OperatorStateMachine.fallback(
                            state = operatorState,
                            id = callId,
                            reason = OperatorFallbackReason.CONFIRMATION_REQUIRED
                        )
                        rememberPendingActionLocked(call, intentPlan, taskDesc, prompt)
                        DispatchDecision.AwaitingConfirmation(buildPendingConfirmationResult(callId, prompt))
                    }

                    is ConfirmationPolicy.Tier.ConditionalConfirm -> {
                        operatorState = OperatorStateMachine.awaitConfirmation(operatorState, callId, tier.prompt)
                        operatorState = OperatorStateMachine.fallback(
                            state = operatorState,
                            id = callId,
                            reason = OperatorFallbackReason.CONFIRMATION_REQUIRED
                        )
                        rememberPendingActionLocked(call, intentPlan, taskDesc, tier.prompt)
                        DispatchDecision.AwaitingConfirmation(buildPendingConfirmationResult(callId, tier.prompt))
                    }

                    ConfirmationPolicy.Tier.Implicit -> {
                        operatorState = OperatorStateMachine.dispatch(operatorState, callId)
                        DispatchDecision.Dispatch(intentPlan)
                    }
                }
            }
        }

        val intentPlan = when (dispatchDecision) {
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

            is DispatchDecision.Dispatch -> dispatchDecision.intentPlan
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var response: JSONObject? = null
            try {
                val result = executeResolvedIntent(
                    callId = callId,
                    callName = callName,
                    call = call,
                    intentPlan = intentPlan
                )

                if (!coroutineContext[Job]!!.isCancelled) {
                    response = buildToolResponse(callId, callName, result)
                }
            } catch (e: CancellationException) {
                throw e
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
                val job = inFlightJobs.remove(id)
                val pendingAction = pendingActions[id]
                if (job == null && pendingAction == null) {
                    return@synchronized null
                }

                pendingTimeouts.remove(id)?.cancel()
                pendingActions.remove(id)
                if (sessionStateManager.pendingConfirmation.value?.id == id) {
                    sessionStateManager.clearPendingConfirmation()
                }

                val toolName = operatorState.calls[id]?.toolName ?: pendingAction?.call?.name ?: id
                operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_by_gemini")
                CancellationTarget(id = id, job = job, toolName = toolName)
            }

            cancelled?.let { target ->
                target.job?.cancel()
                bridge.setToolCallState(target.id, ToolCallStatus.Cancelled(target.toolName))
            }
        }
    }

    fun cancelAll() {
        val cancelled = synchronized(stateLock) {
            val targets = mutableListOf<CancellationTarget>()

            inFlightJobs.forEach { (id, job) ->
                val toolName = operatorState.calls[id]?.toolName ?: id
                operatorState = OperatorStateMachine.cancel(operatorState, id, "cancelled_during_shutdown")
                targets += CancellationTarget(id = id, job = job, toolName = toolName)
            }

            operatorState.calls.values
                .filterIsInstance<OperatorStateMachine.OperatorState.AwaitingConfirmation>()
                .forEach { awaiting ->
                    if (targets.none { it.id == awaiting.id }) {
                        operatorState = OperatorStateMachine.cancel(
                            operatorState,
                            awaiting.id,
                            "cancelled_during_shutdown"
                        )
                        targets += CancellationTarget(
                            id = awaiting.id,
                            job = null,
                            toolName = awaiting.toolName
                        )
                    }
                }

            inFlightJobs.clear()
            pendingActions.clear()
            pendingTimeouts.values.forEach { it.cancel() }
            pendingTimeouts.clear()
            sessionStateManager.clearPendingConfirmation()
            operatorState = OperatorStateMachine.State()
            targets
        }

        for (target in cancelled) {
            target.job?.cancel()
            bridge.setToolCallState(target.id, ToolCallStatus.Cancelled(target.toolName))
        }
    }

    fun invalidatePendingConfirmations(reason: String) {
        val invalidated = synchronized(stateLock) {
            invalidatePendingActionLocked(reason)
        }

        invalidated?.let { pendingAction ->
            bridge.setToolCallState(pendingAction.call.id, ToolCallStatus.Cancelled(pendingAction.call.name))
        }
    }

    private suspend fun executeResolvedIntent(
        callId: String,
        callName: String,
        call: GeminiFunctionCall,
        intentPlan: IntentDispatchPlan
    ): ToolResult {
        return try {
            val routingResult = intentRouter.execute(call, intentPlan)
            val result = routingResult.result

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
                        bridge.setToolCallState(callId, ToolCallStatus.Failed(callName, result.error))
                    }
                }
            }

            sessionStateManager.recordToolResult(callName, result)
            result
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
            failure
        }
    }

    private fun rememberPendingActionLocked(
        call: GeminiFunctionCall,
        intentPlan: IntentDispatchPlan,
        task: String,
        prompt: String
    ) {
        pendingTimeouts.remove(call.id)?.cancel()
        pendingActions[call.id] = PendingAction(call = call, intentPlan = intentPlan)
        sessionStateManager.setPendingConfirmation(
            toolCallId = call.id,
            toolName = call.name,
            task = task,
            reason = prompt
        )
        pendingTimeouts[call.id] = scope.launch {
            delay(PENDING_CONFIRMATION_TIMEOUT_MS)
            val timedOut = synchronized(stateLock) {
                cancelPendingActionLocked(call.id, "timeout")
            }

            timedOut?.let { pendingAction ->
                bridge.setToolCallState(
                    pendingAction.call.id,
                    ToolCallStatus.Cancelled(pendingAction.call.name)
                )
            }
        }
    }

    private fun cancelPendingActionLocked(
        pendingActionId: String,
        reason: String
    ): PendingAction? {
        val pending = sessionStateManager.pendingConfirmation.value
        val pendingAction = pendingActions[pendingActionId]
        if (pending == null || pending.id != pendingActionId || pendingAction == null) {
            return null
        }

        pendingTimeouts.remove(pendingActionId)?.cancel()
        pendingActions.remove(pendingActionId)
        sessionStateManager.clearPendingConfirmation()
        operatorState = OperatorStateMachine.cancel(operatorState, pendingActionId, reason)
        return pendingAction
    }

    private fun invalidatePendingActionLocked(reason: String): PendingAction? {
        val pending = sessionStateManager.pendingConfirmation.value ?: return null
        val pendingAction = pendingActions[pending.id] ?: return null

        pendingTimeouts.remove(pending.id)?.cancel()
        pendingActions.remove(pending.id)
        sessionStateManager.invalidatePendingConfirmations(reason)
        operatorState = OperatorStateMachine.invalidate(operatorState, pending.id, reason)
        return pendingAction
    }

    private fun buildPendingConfirmationResult(callId: String, prompt: String): ToolResult.Success {
        return ToolResult.Success(
            "Confirmation required. pendingActionId=$callId. $prompt Ask the user to approve this action, " +
                "then call confirm_pending with pendingActionId=\"$callId\" and confirm=true. If they decline, " +
                "call confirm_pending with pendingActionId=\"$callId\" and confirm=false."
        )
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
        if (call.name == "confirm_pending") {
            val pendingActionId = (call.args["pendingActionId"] as? String)?.takeIf(String::isNotBlank)
                ?: "pending action"
            val decision = if (call.args["confirm"] == false) "cancel" else "confirm"
            return "$decision $pendingActionId"
        }

        return (call.args["task"] as? String)?.takeIf(String::isNotBlank)
            ?: (call.args["query"] as? String)?.takeIf(String::isNotBlank)
            ?: ""
    }

    private data class PendingAction(
        val call: GeminiFunctionCall,
        val intentPlan: IntentDispatchPlan
    )

    private data class CancellationTarget(
        val id: String,
        val job: Job?,
        val toolName: String
    )

    private sealed interface DispatchDecision {
        data class Rejected(val result: ToolResult.Failure) : DispatchDecision
        data class AwaitingConfirmation(val result: ToolResult.Success) : DispatchDecision
        data class Dispatch(val intentPlan: IntentDispatchPlan) : DispatchDecision
    }
}
