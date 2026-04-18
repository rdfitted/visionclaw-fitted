package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

typealias ToolCallId = String

object OperatorStateMachine {
    const val MAX_CONSECUTIVE_FAILURES = 3

    data class State(
        val calls: Map<ToolCallId, OperatorState> = emptyMap(),
        val consecutiveFailures: Int = 0,
        val circuitBreakerOpen: Boolean = false
    )

    sealed interface OperatorState {
        val context: OperatorContext
        val id: ToolCallId
        val toolName: String
        val enteredAtMs: Long

        data class Proposed(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val task: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Validated(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val task: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class AwaitingConfirmation(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val task: String,
            val reason: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Dispatched(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val task: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Completed(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val result: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Failed(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val error: String,
            val failureCount: Int,
            val circuitBreakerOpen: Boolean,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Rejected(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Cancelled(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String? = null,
            override val enteredAtMs: Long
        ) : OperatorState

        data class Invalidated(
            override val context: OperatorContext,
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String,
            override val enteredAtMs: Long
        ) : OperatorState
    }

    fun propose(
        state: State,
        context: OperatorContext,
        toolName: String,
        task: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        return state.transition(
            from = null,
            to = OperatorState.Proposed(
                context = context,
                id = context.toolCallId,
                toolName = toolName,
                task = task,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.PROPOSED
        )
    }

    fun validate(state: State, id: ToolCallId, nowMs: Long = System.currentTimeMillis()): State {
        val proposed = state.requireCall<OperatorState.Proposed>(id)
        return state.transition(
            from = proposed,
            to = OperatorState.Validated(
                context = proposed.context,
                id = id,
                toolName = proposed.toolName,
                task = proposed.task,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.VALIDATED
        )
    }

    fun awaitConfirmation(
        state: State,
        id: ToolCallId,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val validated = state.requireCall<OperatorState.Validated>(id)
        return state.transition(
            from = validated,
            to = OperatorState.AwaitingConfirmation(
                context = validated.context,
                id = id,
                toolName = validated.toolName,
                task = validated.task,
                reason = reason,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.CONFIRMATION_REQUIRED,
            reason = reason
        )
    }

    fun confirm(state: State, id: ToolCallId, nowMs: Long = System.currentTimeMillis()): State {
        val awaiting = state.requireCall<OperatorState.AwaitingConfirmation>(id)
        return state.transition(
            from = awaiting,
            to = OperatorState.Validated(
                context = awaiting.context,
                id = id,
                toolName = awaiting.toolName,
                task = awaiting.task,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.CONFIRMED
        )
    }

    fun dispatch(state: State, id: ToolCallId, nowMs: Long = System.currentTimeMillis()): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        val dispatched = when (call) {
            is OperatorState.Validated -> {
                OperatorState.Dispatched(
                    context = call.context,
                    id = id,
                    toolName = call.toolName,
                    task = call.task,
                    enteredAtMs = nowMs
                )
            }
            is OperatorState.AwaitingConfirmation -> {
                OperatorState.Dispatched(
                    context = call.context,
                    id = id,
                    toolName = call.toolName,
                    task = call.task,
                    enteredAtMs = nowMs
                )
            }
            else -> error("Cannot dispatch ${call::class.simpleName} for $id")
        }
        return state.transition(
            from = call,
            to = dispatched,
            event = OperatorEvent.DISPATCHED
        )
    }

    fun complete(
        state: State,
        id: ToolCallId,
        result: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val dispatched = state.requireCall<OperatorState.Dispatched>(id)
        return state.transition(
            from = dispatched,
            to = OperatorState.Completed(
                context = dispatched.context,
                id = id,
                toolName = dispatched.toolName,
                result = result,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.COMPLETED,
            consecutiveFailures = 0,
            circuitBreakerOpen = false
        )
    }

    fun fail(
        state: State,
        id: ToolCallId,
        errorMessage: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val dispatched = state.requireCall<OperatorState.Dispatched>(id)
        val failureCount = state.consecutiveFailures + 1
        val circuitBreakerOpen = failureCount >= MAX_CONSECUTIVE_FAILURES
        return state.transition(
            from = dispatched,
            to = OperatorState.Failed(
                context = dispatched.context,
                id = id,
                toolName = dispatched.toolName,
                error = errorMessage,
                failureCount = failureCount,
                circuitBreakerOpen = circuitBreakerOpen,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.FAILED,
            consecutiveFailures = failureCount,
            circuitBreakerOpen = circuitBreakerOpen
        )
    }

    fun reject(
        state: State,
        id: ToolCallId,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.transition(
            from = call,
            to = OperatorState.Rejected(
                context = call.context,
                id = id,
                toolName = call.toolName,
                reason = reason,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.REJECTED,
            reason = reason
        )
    }

    fun cancel(
        state: State,
        id: ToolCallId,
        reason: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.transition(
            from = call,
            to = OperatorState.Cancelled(
                context = call.context,
                id = id,
                toolName = call.toolName,
                reason = reason,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.CANCELLED,
            reason = reason
        )
    }

    fun invalidate(
        state: State,
        id: ToolCallId,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.transition(
            from = call,
            to = OperatorState.Invalidated(
                context = call.context,
                id = id,
                toolName = call.toolName,
                reason = reason,
                enteredAtMs = nowMs
            ),
            event = OperatorEvent.INVALIDATED,
            reason = reason
        )
    }

    fun fallback(state: State, id: ToolCallId, reason: String): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        OperatorLog.log(
            context = call.context,
            event = OperatorEvent.FALLBACK_TAKEN,
            toolName = call.toolName,
            fromState = call.logStateName(),
            toState = call.logStateName(),
            latencyMs = 0,
            reason = reason
        )
        return state
    }

    private inline fun <reified T : OperatorState> State.requireCall(id: ToolCallId): T {
        val call = calls[id] ?: error("Missing call state for $id")
        return call as? T ?: error("Expected ${T::class.simpleName} for $id but found ${call::class.simpleName}")
    }

    private fun State.transition(
        from: OperatorState?,
        to: OperatorState,
        event: OperatorEvent,
        reason: String? = null,
        consecutiveFailures: Int = this.consecutiveFailures,
        circuitBreakerOpen: Boolean = this.circuitBreakerOpen
    ): State {
        OperatorLog.log(
            context = to.context,
            event = event,
            toolName = to.toolName,
            fromState = from?.logStateName() ?: "none",
            toState = to.logStateName(),
            latencyMs = from?.let { to.enteredAtMs - it.enteredAtMs } ?: 0,
            reason = reason
        )
        return copy(
            calls = calls + (to.id to to),
            consecutiveFailures = consecutiveFailures,
            circuitBreakerOpen = circuitBreakerOpen
        )
    }

    private fun OperatorState.logStateName(): String = when (this) {
        is OperatorState.Proposed -> "proposed"
        is OperatorState.Validated -> "validated"
        is OperatorState.AwaitingConfirmation -> "awaiting_confirmation"
        is OperatorState.Dispatched -> "dispatched"
        is OperatorState.Completed -> "completed"
        is OperatorState.Failed -> "failed"
        is OperatorState.Rejected -> "rejected"
        is OperatorState.Cancelled -> "cancelled"
        is OperatorState.Invalidated -> "invalidated"
    }
}
