package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.handlers

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing.ValidationIssue
import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> OpenClawBridge.executeStructuredHandler(
    call: GeminiFunctionCall,
    validateArgs: (Map<String, Any?>) -> ValidationIssue?,
    parsePayload: (Map<String, Any?>) -> T?,
    invalidPayloadError: String,
    invalidPayloadHint: String,
    bridgeFailureHint: String,
    crossinline beforeDelegate: (T) -> Unit = {},
    crossinline buildTask: (T) -> String,
): ToolResult {
    validateArgs(call.args)?.let { issue ->
        return issue.toFailure()
    }

    val payload = parsePayload(call.args)
        ?: return invalidPayloadFailure(
            error = invalidPayloadError,
            hint = invalidPayloadHint
        )

    beforeDelegate(payload)

    return delegateStructuredTask(
        call = call,
        task = buildTask(payload),
        bridgeFailureHint = bridgeFailureHint
    )
}

internal fun ValidationIssue.toFailure(): ToolResult.Failure {
    return ToolResult.Failure(
        error = error,
        hint = hint
    )
}

internal fun invalidPayloadFailure(error: String, hint: String): ToolResult.Failure {
    return ToolResult.Failure(
        error = error,
        hint = hint
    )
}

internal suspend fun OpenClawBridge.delegateStructuredTask(
    call: GeminiFunctionCall,
    task: String,
    bridgeFailureHint: String
): ToolResult {
    return try {
        delegateTask(
            callId = call.id,
            task = task,
            toolName = call.name
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.Failure(
            error = e.message ?: "Unknown error in OpenClawBridge",
            hint = bridgeFailureHint
        )
    }
}
