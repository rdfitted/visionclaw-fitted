package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import com.google.gson.Gson
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
    const val CONFIRMATION_REQUIRED = "confirmation_required"
}

object OperatorLog {
    private const val TAG = "OperatorLog"
    private val gson = Gson()

    fun log(
        context: OperatorContext,
        event: OperatorEvent,
        toolName: String,
        fromState: String,
        toState: String,
        latencyMs: Long,
        reason: String? = null
    ) {
        Timber.tag(TAG).i(buildPayload(
            context = context,
            event = event,
            toolName = toolName,
            fromState = fromState,
            toState = toState,
            latencyMs = latencyMs,
            reason = reason
        ))
    }

    private fun buildPayload(
        context: OperatorContext,
        event: OperatorEvent,
        toolName: String,
        fromState: String,
        toState: String,
        latencyMs: Long,
        reason: String?
    ): String {
        val fields = linkedMapOf(
            "sessionId" to context.sessionId,
            "turnId" to context.turnId,
            "toolCallId" to context.toolCallId,
            "proposalId" to context.proposalId,
            "event" to event.wireName,
            "toolName" to toolName,
            "fromState" to fromState,
            "toState" to toState,
            "latencyMs" to latencyMs.coerceAtLeast(0)
        )
        reason?.let { fields["reason"] = it }

        return gson.toJson(fields)
    }
}
