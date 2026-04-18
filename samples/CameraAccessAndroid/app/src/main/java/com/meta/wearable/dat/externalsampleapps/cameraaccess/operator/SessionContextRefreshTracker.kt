package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiLiveService

class SessionContextRefreshTracker(
    private val sessionStateManager: SessionStateManager,
    private val geminiLiveService: GeminiLiveService
) {
    private var lastSentContextHash: Int = sessionStateManager.sessionContextBlock().hashCode()

    fun markCurrentContextSent() {
        lastSentContextHash = sessionStateManager.sessionContextBlock().hashCode()
    }

    fun sendRefreshIfChanged() {
        val sessionContext = sessionStateManager.sessionContextBlock()
        val contextHash = sessionContext.hashCode()
        if (contextHash == lastSentContextHash) {
            return
        }

        lastSentContextHash = contextHash
        if (sessionContext.isBlank()) {
            return
        }

        geminiLiveService.sendTextMessage(
            "System note for future turns only. Do not acknowledge this out loud. " +
                "Refresh your rolling session context with the latest state.\n$sessionContext"
        )
    }
}
