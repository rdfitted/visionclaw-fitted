package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import org.json.JSONObject
import timber.log.Timber

data class OperatorContext(
    val sessionId: String,
    val turnId: String,
    val toolCallId: ToolCallId,
    val proposalId: String = toolCallId
)

enum class OperatorEvent(val wireName: String) {
    PROPOSED("proposed"),
    VALIDATED("validated"),
    REJECTED("rejected"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    CONFIRMED("confirmed"),
    CANCELLED("cancelled"),
    INVALIDATED("invalidated"),
    DISPATCHED("dispatched"),
    COMPLETED("completed"),
    FAILED("failed"),
    FALLBACK_TAKEN("fallback_taken")
}

object OperatorFallbackReason {
    const val NO_MATCHING_TOOL = "no_matching_tool"
    const val HANDLER_UNAVAILABLE = "handler_unavailable"
    const val KILL_SWITCH = "kill_switch"
}

object OperatorLog {
    private const val TAG = "OperatorLog"

    fun log(
        context: OperatorContext,
        event: OperatorEvent,
        toolName: String,
        fromState: String,
        toState: String,
        latencyMs: Long,
        reason: String? = null
    ) {
        val payload = JSONObject().apply {
            put("sessionId", context.sessionId)
            put("turnId", context.turnId)
            put("toolCallId", context.toolCallId)
            put("proposalId", context.proposalId)
            put("event", event.wireName)
            put("toolName", toolName)
            put("fromState", fromState)
            put("toState", toState)
            put("latencyMs", latencyMs.coerceAtLeast(0))
            reason?.let { put("reason", it) }
        }

        Timber.tag(TAG).i(payload.toString())
    }
}
