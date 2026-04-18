package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperatorStateMachineTest {
    @Test
    fun proposedValidatedAwaitingConfirmationDispatchedCompletedFlowResetsCircuitBreaker() {
        var state = OperatorStateMachine.State(consecutiveFailures = 2, circuitBreakerOpen = false)

        state = OperatorStateMachine.propose(state, context("call-1"), "execute", "Draft a reply")
        assertIs<OperatorStateMachine.OperatorState.Proposed>(state.calls.getValue("call-1"))

        state = OperatorStateMachine.validate(state, "call-1")
        assertIs<OperatorStateMachine.OperatorState.Validated>(state.calls.getValue("call-1"))

        state = OperatorStateMachine.awaitConfirmation(state, "call-1", "Outbound message")
        assertIs<OperatorStateMachine.OperatorState.AwaitingConfirmation>(state.calls.getValue("call-1"))

        state = OperatorStateMachine.dispatch(state, "call-1")
        assertIs<OperatorStateMachine.OperatorState.Dispatched>(state.calls.getValue("call-1"))

        state = OperatorStateMachine.complete(state, "call-1", "Sent")
        assertIs<OperatorStateMachine.OperatorState.Completed>(state.calls.getValue("call-1"))
        assertEquals(0, state.consecutiveFailures)
        assertFalse(state.circuitBreakerOpen)
    }

    @Test
    fun confirmTransitionReturnsValidatedBeforeDispatching() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-confirm"), "execute", "Send update")
        state = OperatorStateMachine.validate(state, "call-confirm")
        state = OperatorStateMachine.awaitConfirmation(state, "call-confirm", "Needs confirmation")

        state = OperatorStateMachine.confirm(state, "call-confirm")
        val validated = assertIs<OperatorStateMachine.OperatorState.Validated>(state.calls.getValue("call-confirm"))
        assertEquals("Send update", validated.task)

        state = OperatorStateMachine.dispatch(state, "call-confirm")
        assertIs<OperatorStateMachine.OperatorState.Dispatched>(state.calls.getValue("call-confirm"))
    }

    @Test
    fun proposedValidatedDispatchedFailedFlowOpensCircuitBreakerOnThirdFailure() {
        var state = OperatorStateMachine.State()

        repeat(3) { index ->
            val callId = "failure-$index"
            state = OperatorStateMachine.propose(state, context(callId), "execute", "Task $index")
            state = OperatorStateMachine.validate(state, callId)
            state = OperatorStateMachine.dispatch(state, callId)
            state = OperatorStateMachine.fail(state, callId, "Boom $index")
        }

        val failed = assertIs<OperatorStateMachine.OperatorState.Failed>(state.calls.getValue("failure-2"))
        assertEquals(3, failed.failureCount)
        assertTrue(failed.circuitBreakerOpen)
        assertEquals(3, state.consecutiveFailures)
        assertTrue(state.circuitBreakerOpen)
    }

    @Test
    fun rejectTransitionPreservesIndependentCallState() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-1"), "execute", "One")
        state = OperatorStateMachine.validate(state, "call-1")
        state = OperatorStateMachine.propose(state, context("call-2"), "execute", "Two")

        state = OperatorStateMachine.reject(state, "call-1", "Circuit breaker open")

        assertIs<OperatorStateMachine.OperatorState.Rejected>(state.calls.getValue("call-1"))
        assertIs<OperatorStateMachine.OperatorState.Proposed>(state.calls.getValue("call-2"))
    }

    @Test
    fun cancelTransitionMarksOnlyTargetCallCancelled() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-1"), "execute", "One")
        state = OperatorStateMachine.validate(state, "call-1")
        state = OperatorStateMachine.dispatch(state, "call-1")
        state = OperatorStateMachine.propose(state, context("call-2"), "execute", "Two")

        state = OperatorStateMachine.cancel(state, "call-1", "User cancelled")

        val cancelled = assertIs<OperatorStateMachine.OperatorState.Cancelled>(state.calls.getValue("call-1"))
        assertEquals("User cancelled", cancelled.reason)
        assertIs<OperatorStateMachine.OperatorState.Proposed>(state.calls.getValue("call-2"))
    }

    @Test
    fun invalidateTransitionStoresReason() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-1"), "execute", "")
        state = OperatorStateMachine.invalidate(state, "call-1", "Missing task payload")

        val invalidated = assertIs<OperatorStateMachine.OperatorState.Invalidated>(state.calls.getValue("call-1"))
        assertEquals("Missing task payload", invalidated.reason)
    }

    @Test
    fun fallbackTransitionStoresReasonWithoutChangingCallState() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-fallback"), "execute", "Find the weather")
        state = OperatorStateMachine.validate(state, "call-fallback")
        val beforeFallback = state.calls.getValue("call-fallback")

        state = OperatorStateMachine.fallback(state, "call-fallback", "kill_switch")

        assertEquals("kill_switch", state.lastFallbackReason)
        assertEquals(beforeFallback, state.calls.getValue("call-fallback"))
    }

    @Test
    fun concurrentProposalsRemainIndependentAcrossTransitions() {
        var state = OperatorStateMachine.State()

        state = OperatorStateMachine.propose(state, context("call-1"), "execute", "Task one")
        state = OperatorStateMachine.propose(state, context("call-2"), "execute", "Task two")
        state = OperatorStateMachine.validate(state, "call-1")
        state = OperatorStateMachine.dispatch(state, "call-1")

        assertIs<OperatorStateMachine.OperatorState.Dispatched>(state.calls.getValue("call-1"))
        val proposed = assertIs<OperatorStateMachine.OperatorState.Proposed>(state.calls.getValue("call-2"))
        assertEquals("Task two", proposed.task)
    }

    private fun context(callId: String) = OperatorContext(
        sessionId = "session-1",
        turnId = "turn-$callId",
        toolCallId = callId
    )
}
