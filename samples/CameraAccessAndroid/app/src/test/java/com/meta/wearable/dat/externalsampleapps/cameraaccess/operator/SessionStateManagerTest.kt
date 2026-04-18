package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiLiveService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionStateManagerTest {
    @Test
    fun sessionContextBlockRespectsPriorityAndBudget() {
        val clock = TestClock()
        val manager = SessionStateManager(TestBridge(), nowProvider = clock::now)

        manager.reset("session-1")
        manager.updateObjective("Send a detailed status update to Samantha Carter about the VisionClaw rollout in Honolulu and include the revised deadline.")
        manager.setPendingConfirmation(
            toolCallId = "call-1",
            toolName = "execute",
            task = "Send Samantha Carter the status update by email",
            reason = "Outbound communication needs approval"
        )
        manager.observeText("Contact Samantha Carter at samantha@example.com or +1 (808) 555-0100")
        manager.recordToolResult("search_web", ToolResult.Success("Found release notes, stakeholder summary, and deployment checklist for VisionClaw."))
        manager.recordToolResult("execute", ToolResult.Failure("Message send failed because the outbound connector is offline."))

        val block = manager.sessionContextBlock()

        assertTrue(block.startsWith("Session context:\nObjective:"))
        assertTrue(block.contains("Pending confirmation:"))
        assertTrue(block.contains("Recent entities:"))
        assertTrue(block.contains("Recent tool results:"))
        assertTrue(SessionStateManager.estimateTokens(block) <= 200)
    }

    @Test
    fun recentEntitiesPruneAfterTenMinutesOfInactivity() {
        val clock = TestClock()
        val manager = SessionStateManager(TestBridge(), nowProvider = clock::now)

        manager.reset("session-1")
        manager.observeText("Reach Alice Johnson at alice@example.com")
        assertEquals(2, manager.recentEntities.value.size)

        clock.advanceBy(10 * 60 * 1000L + 1)
        manager.sessionContextBlock()

        assertTrue(manager.recentEntities.value.isEmpty())
    }

    @Test
    fun invalidatePendingConfirmationsClearsFlow() {
        val manager = SessionStateManager(TestBridge())

        manager.reset("session-1")
        manager.setPendingConfirmation(
            toolCallId = "call-1",
            toolName = "execute",
            task = "Send the email",
            reason = "Needs approval"
        )

        manager.invalidatePendingConfirmations("session_reset")

        assertNull(manager.pendingConfirmation.value)
    }

    @Test
    fun resetOnDisconnectClearsObjectiveEntitiesPendingAndToolResults() {
        val manager = SessionStateManager(TestBridge())

        manager.reset("session-1")
        manager.updateObjective("Email Marcus about the budget")
        manager.observeText("Marcus can be reached at marcus@example.com")
        manager.setPendingConfirmation("call-1", "execute", "Email Marcus", "Needs approval")
        manager.recordToolResult("execute", ToolResult.Success("Sent"))

        manager.reset("session-2")

        assertNull(manager.objective.value)
        assertTrue(manager.recentEntities.value.isEmpty())
        assertNull(manager.pendingConfirmation.value)
        assertTrue(manager.recentToolResults.value.isEmpty())
        assertEquals("", manager.sessionContextBlock())
    }

    @Test
    fun sessionContextBlockIsDeterministicForStableState() {
        val clock = TestClock()
        val manager = SessionStateManager(TestBridge(), nowProvider = clock::now)

        manager.reset("session-1")
        manager.updateObjective("Send the launch update")
        manager.observeText("sam@example.com")
        manager.recordToolResult("execute", ToolResult.Success("Sent successfully"))

        val first = manager.sessionContextBlock()
        val second = manager.sessionContextBlock()

        assertEquals(first, second)
        assertTrue(first.startsWith("Session context:\nObjective: Send the launch update"))
        assertTrue(first.contains("Recent entities:"))
        assertTrue(first.contains("Recent tool results: execute:ok:Sent successfully"))
    }

    @Test
    fun sessionContextRefreshTrackerResendsUpdatedContextAfterStateChange() {
        val manager = SessionStateManager(TestBridge())
        val geminiService = RecordingGeminiLiveService()
        val refreshTracker = SessionContextRefreshTracker(manager, geminiService)

        manager.reset("session-1")
        refreshTracker.markCurrentContextSent()

        manager.updateObjective("Send the launch update")
        val refreshedContext = manager.sessionContextBlock()
        refreshTracker.sendRefreshIfChanged()

        assertEquals(1, geminiService.messages.size)
        assertTrue(geminiService.messages.first().contains(refreshedContext))

        refreshTracker.sendRefreshIfChanged()
        assertEquals(1, geminiService.messages.size)

        manager.recordToolResult("execute", ToolResult.Success("Sent successfully"))
        refreshTracker.sendRefreshIfChanged()
        assertEquals(2, geminiService.messages.size)
    }

    @Test
    fun conversationHistoryPassthroughUsesBridgeFlow() {
        val bridge = OpenClawBridge()
        val manager = SessionStateManager(bridge)

        assertSame(bridge.conversationHistory, manager.conversationHistory)
    }

    private class TestClock {
        private var nowMs: Long = 1_000L

        fun now(): Long = nowMs

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private class TestBridge : OpenClawBridge() {
        override fun resetSession() {
        }
    }

    private class RecordingGeminiLiveService : GeminiLiveService() {
        val messages = mutableListOf<String>()

        override fun sendTextMessage(text: String) {
            messages += text
        }
    }
}
