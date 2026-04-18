package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager

interface PendingConfirmationController {
    suspend fun confirmPending(pendingActionId: String): ToolResult
    suspend fun cancelPending(pendingActionId: String, reason: String): ToolResult
}

class ConfirmPendingHandler(
    private val sessionStateManager: SessionStateManager,
    private val controller: PendingConfirmationController
) : ToolHandler {
    override suspend fun execute(call: GeminiFunctionCall): ToolResult {
        val requestedId = (call.args["pendingActionId"] as? String)?.trim().orEmpty()
        val confirm = call.args["confirm"] as? Boolean
            ?: return ToolResult.Failure(
                error = "Missing confirm argument",
                hint = "Provide confirm=true to proceed or confirm=false to cancel the pending action."
            )

        val pending = sessionStateManager.pendingConfirmation.value
        if (pending == null || requestedId.isBlank() || pending.id != requestedId) {
            return ToolResult.Success("No pending action matched that confirmation request.")
        }

        return if (confirm) {
            controller.confirmPending(requestedId)
        } else {
            controller.cancelPending(requestedId, "user_declined")
        }
    }
}
