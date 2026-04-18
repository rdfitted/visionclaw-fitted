package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ConversationHistoryFlowTest {
    @Test
    fun conversationHistoryTrimsToMaxHistoryTurns() {
        val bridge = OpenClawBridge()

        repeat(OpenClawBridge.MAX_HISTORY_TURNS + 1) { index ->
            bridge.appendConversationEntry(role = "user", content = "user-$index", timestamp = index.toLong())
            bridge.appendConversationEntry(role = "assistant", content = "assistant-$index", timestamp = index.toLong())
        }

        val history = bridge.conversationHistory.value

        assertEquals(OpenClawBridge.MAX_HISTORY_TURNS * 2, history.size)
        assertEquals("user-1", history.first().content)
        assertEquals("assistant-${OpenClawBridge.MAX_HISTORY_TURNS}", history.last().content)
    }

    @Test
    fun sessionStateManagerExposesBridgeConversationHistoryFlowInstance() {
        val bridge = OpenClawBridge()
        val manager = SessionStateManager(bridge)

        assertSame(bridge.conversationHistory, manager.conversationHistory)
    }
}
