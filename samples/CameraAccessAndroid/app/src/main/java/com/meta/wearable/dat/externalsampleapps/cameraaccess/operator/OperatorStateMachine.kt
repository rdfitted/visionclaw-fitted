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
        val id: ToolCallId
        val toolName: String

        data class Proposed(
            override val id: ToolCallId,
            override val toolName: String,
            val task: String
        ) : OperatorState

        data class Validated(
            override val id: ToolCallId,
            override val toolName: String,
            val task: String
        ) : OperatorState

        data class AwaitingConfirmation(
            override val id: ToolCallId,
            override val toolName: String,
            val task: String,
            val reason: String
        ) : OperatorState

        data class Dispatched(
            override val id: ToolCallId,
            override val toolName: String,
            val task: String
        ) : OperatorState

        data class Completed(
            override val id: ToolCallId,
            override val toolName: String,
            val result: String
        ) : OperatorState

        data class Failed(
            override val id: ToolCallId,
            override val toolName: String,
            val error: String,
            val failureCount: Int,
            val circuitBreakerOpen: Boolean
        ) : OperatorState

        data class Rejected(
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String
        ) : OperatorState

        data class Cancelled(
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String? = null
        ) : OperatorState

        data class Invalidated(
            override val id: ToolCallId,
            override val toolName: String,
            val reason: String
        ) : OperatorState
    }

    fun propose(state: State, id: ToolCallId, toolName: String, task: String): State {
        return state.withCall(OperatorState.Proposed(id = id, toolName = toolName, task = task))
    }

    fun validate(state: State, id: ToolCallId): State {
        val proposed = state.requireCall<OperatorState.Proposed>(id)
        return state.withCall(
            OperatorState.Validated(id = id, toolName = proposed.toolName, task = proposed.task)
        )
    }

    fun awaitConfirmation(state: State, id: ToolCallId, reason: String): State {
        val validated = state.requireCall<OperatorState.Validated>(id)
        return state.withCall(
            OperatorState.AwaitingConfirmation(
                id = id,
                toolName = validated.toolName,
                task = validated.task,
                reason = reason
            )
        )
    }

    fun dispatch(state: State, id: ToolCallId): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        val dispatched = when (call) {
            is OperatorState.Validated -> {
                OperatorState.Dispatched(id = id, toolName = call.toolName, task = call.task)
            }
            is OperatorState.AwaitingConfirmation -> {
                OperatorState.Dispatched(id = id, toolName = call.toolName, task = call.task)
            }
            else -> error("Cannot dispatch ${call::class.simpleName} for $id")
        }
        return state.withCall(dispatched)
    }

    fun complete(state: State, id: ToolCallId, result: String): State {
        val dispatched = state.requireCall<OperatorState.Dispatched>(id)
        return state.copy(
            calls = state.calls + (
                id to OperatorState.Completed(
                    id = id,
                    toolName = dispatched.toolName,
                    result = result
                )
            ),
            consecutiveFailures = 0,
            circuitBreakerOpen = false
        )
    }

    fun fail(state: State, id: ToolCallId, errorMessage: String): State {
        val dispatched = state.requireCall<OperatorState.Dispatched>(id)
        val failureCount = state.consecutiveFailures + 1
        val circuitBreakerOpen = failureCount >= MAX_CONSECUTIVE_FAILURES
        return state.copy(
            calls = state.calls + (
                id to OperatorState.Failed(
                    id = id,
                    toolName = dispatched.toolName,
                    error = errorMessage,
                    failureCount = failureCount,
                    circuitBreakerOpen = circuitBreakerOpen
                )
            ),
            consecutiveFailures = failureCount,
            circuitBreakerOpen = circuitBreakerOpen
        )
    }

    fun reject(state: State, id: ToolCallId, reason: String): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.withCall(
            OperatorState.Rejected(id = id, toolName = call.toolName, reason = reason)
        )
    }

    fun cancel(state: State, id: ToolCallId, reason: String? = null): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.withCall(
            OperatorState.Cancelled(id = id, toolName = call.toolName, reason = reason)
        )
    }

    fun invalidate(state: State, id: ToolCallId, reason: String): State {
        val call = state.calls[id] ?: error("Missing call state for $id")
        return state.withCall(
            OperatorState.Invalidated(id = id, toolName = call.toolName, reason = reason)
        )
    }

    private inline fun <reified T : OperatorState> State.requireCall(id: ToolCallId): T {
        val call = calls[id] ?: error("Missing call state for $id")
        return call as? T ?: error("Expected ${T::class.simpleName} for $id but found ${call::class.simpleName}")
    }

    private fun State.withCall(call: OperatorState): State {
        return copy(calls = calls + (call.id to call))
    }
}
