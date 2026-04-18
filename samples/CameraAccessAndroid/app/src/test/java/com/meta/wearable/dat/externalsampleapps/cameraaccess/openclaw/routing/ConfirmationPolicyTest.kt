package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.OperatorStateMachine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.ConfirmationPolicy as OperatorConfirmationPolicy
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class ConfirmationPolicyTest {
    @BeforeTest
    fun initializeConfirmationPolicy() {
        setConfirmationPolicy(OperatorConfirmationPolicy.Standard)
    }

    @AfterTest
    fun resetConfirmationPolicy() {
        setConfirmationPolicy(OperatorConfirmationPolicy.Standard)
    }

    @Test
    fun evaluatesMoreThanTenIntentShapesAcrossConfirmationTiers() {
        val sessionStateManager = SessionStateManager(TestBridge()).apply {
            reset("session-1")
            observeText("sam@example.com")
        }

        setConfirmationPolicy(OperatorConfirmationPolicy.Standard)
        assertImplicit(call(name = "search_web", query = "weather honolulu"), sessionStateManager)
        assertImplicit(
            GeminiFunctionCall(
                id = "confirm-call",
                name = "confirm_pending",
                args = mapOf("pendingActionId" to "call-1", "confirm" to true)
            ),
            sessionStateManager
        )
        assertConditional(call(task = "send the status update"), sessionStateManager, "Confirm sending this to sam@example.com.")
        assertConditional(call(task = "text the revised ETA"), sessionStateManager, "Confirm sending this to sam@example.com.")
        assertConditional(call(task = "reply with the latest build status"), sessionStateManager, "Confirm sending this to sam@example.com.")
        assertConditional(
            call(task = "buy more charging cables"),
            sessionStateManager,
            "Confirm the item, merchant, amount, or reservation details before proceeding."
        )
        assertConditional(
            call(task = "book a table for two"),
            sessionStateManager,
            "Confirm the item, merchant, amount, or reservation details before proceeding."
        )
        assertConditional(
            call(task = "delete the draft note"),
            sessionStateManager,
            "Confirm before deleting, canceling, or changing existing data."
        )
        assertConditional(
            call(task = "cancel the old reminder"),
            sessionStateManager,
            "Confirm before deleting, canceling, or changing existing data."
        )
        assertConditional(
            call(task = "turn on the office lights"),
            sessionStateManager,
            "Confirm the device or automation change before proceeding."
        )
        assertImplicit(call(task = "summarize the meeting notes"), sessionStateManager)

        setConfirmationPolicy(OperatorConfirmationPolicy.Minimal)
        assertImplicit(call(task = "delete the draft note"), sessionStateManager)

        setConfirmationPolicy(OperatorConfirmationPolicy.AlwaysConfirmOutbound)
        assertAlwaysConfirm(call(task = "send the status update"), sessionStateManager)
        assertImplicit(call(task = "summarize the meeting notes"), sessionStateManager)
    }

    @Test
    fun confirmPendingWithBadIdReturnsNoPendingAction() {
        runBlocking {
            val sessionStateManager = SessionStateManager(TestBridge()).apply {
                reset("session-1")
                setPendingConfirmation(
                    toolCallId = "call-1",
                    toolName = "execute",
                    task = "Send the update",
                    reason = "Needs approval"
                )
            }
            var controllerInvoked = false
            val handler = ConfirmPendingHandler(
                sessionStateManager = sessionStateManager,
                controller = object : PendingConfirmationController {
                    override suspend fun confirmPending(pendingActionId: String): ToolResult {
                        controllerInvoked = true
                        return ToolResult.Success("confirmed")
                    }

                    override suspend fun cancelPending(pendingActionId: String, reason: String): ToolResult {
                        controllerInvoked = true
                        return ToolResult.Success("cancelled")
                    }
                }
            )

            val result = handler.execute(
                GeminiFunctionCall(
                    id = "confirm-invalid",
                    name = "confirm_pending",
                    args = mapOf("pendingActionId" to "yes", "confirm" to true)
                )
            )

            assertFalse(controllerInvoked)
            assertEquals(
                "No pending action matched that confirmation request.",
                assertIs<ToolResult.Success>(result).result
            )
        }
    }

    @Test
    fun newProposalInvalidatesPriorPendingAction() {
        runBlocking {
            val bridge = TestBridge()
            val sessionStateManager = SessionStateManager(bridge).apply { reset("session-1") }
            val router = ToolCallRouter(
                bridge = bridge,
                scope = this,
                sessionStateManager = sessionStateManager
            )

            router.dispatch(call(id = "call-1", task = "send the first update")) { }
            router.dispatch(call(id = "call-2", task = "send the second update")) { }

            assertEquals("call-2", sessionStateManager.pendingConfirmation.value?.id)
            assertIs<ToolCallStatus.Cancelled>(bridge.toolCallStates.value.getValue("call-1").status)
            assertIs<OperatorStateMachine.OperatorState.Invalidated>(
                router.operatorState.value.calls.getValue("call-1")
            )
            assertIs<OperatorStateMachine.OperatorState.AwaitingConfirmation>(
                router.operatorState.value.calls.getValue("call-2")
            )
        }
    }

    @Test
    fun pendingConfirmationTimeoutAutoCancelsAfterThirtySeconds() {
        runBlocking {
            val bridge = TestBridge()
            val sessionStateManager = SessionStateManager(bridge).apply { reset("session-1") }
            val router = ToolCallRouter(
                bridge = bridge,
                scope = this,
                sessionStateManager = sessionStateManager,
                pendingConfirmationTimeoutMs = 20L
            )

            router.dispatch(call(id = "call-timeout", task = "send the timeout update")) { }
            delay(50)

            assertEquals(null, sessionStateManager.pendingConfirmation.value)
            assertIs<ToolCallStatus.Cancelled>(bridge.toolCallStates.value.getValue("call-timeout").status)
            assertIs<OperatorStateMachine.OperatorState.Cancelled>(
                router.operatorState.value.calls.getValue("call-timeout")
            )
        }
    }

    private fun assertImplicit(call: GeminiFunctionCall, sessionStateManager: SessionStateManager) {
        assertIs<ConfirmationPolicy.Tier.Implicit>(ConfirmationPolicy.evaluate(call, sessionStateManager))
    }

    private fun assertConditional(
        call: GeminiFunctionCall,
        sessionStateManager: SessionStateManager,
        prompt: String
    ) {
        val tier = assertIs<ConfirmationPolicy.Tier.ConditionalConfirm>(
            ConfirmationPolicy.evaluate(call, sessionStateManager)
        )
        assertEquals(prompt, tier.prompt)
    }

    private fun assertAlwaysConfirm(call: GeminiFunctionCall, sessionStateManager: SessionStateManager) {
        assertIs<ConfirmationPolicy.Tier.AlwaysConfirm>(ConfirmationPolicy.evaluate(call, sessionStateManager))
    }

    private fun call(
        id: String = "call-${nextId++}",
        name: String = "execute",
        task: String? = null,
        query: String? = null
    ): GeminiFunctionCall {
        val args = buildMap<String, Any?> {
            task?.let { put("task", it) }
            query?.let { put("query", it) }
        }
        return GeminiFunctionCall(id = id, name = name, args = args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setConfirmationPolicy(policy: OperatorConfirmationPolicy) {
        val field = SettingsManager::class.java.getDeclaredField("_confirmationPolicy")
        field.isAccessible = true
        val flow = field.get(SettingsManager) as MutableStateFlow<OperatorConfirmationPolicy>
        flow.value = policy
    }

    companion object {
        private var nextId: Int = 0
    }

    private class TestBridge : OpenClawBridge() {
        override fun resetSession() {
        }
    }
}
