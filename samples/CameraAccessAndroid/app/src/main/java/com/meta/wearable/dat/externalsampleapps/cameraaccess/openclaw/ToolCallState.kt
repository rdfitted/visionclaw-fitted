package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.ToolCallId

data class ToolCallState(
    val status: ToolCallStatus,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

fun Map<ToolCallId, ToolCallState>.latestStatus(): ToolCallStatus {
    return values.maxByOrNull(ToolCallState::updatedAtMillis)?.status ?: ToolCallStatus.Idle
}
