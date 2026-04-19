package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

enum class ResponseMode(
    val storageValue: String,
    val label: String
) {
    FAST("fast", "Fast"),
    NORMAL("normal", "Normal"),
    FOCUSED("focused", "Focused"),
    SILENT_ACT("silent_act", "Silent + Act");

    fun helperText(): String = when (this) {
        FAST -> "Keep spoken replies brief and move quickly."
        NORMAL -> "Use the default balance of speed and clarity."
        FOCUSED -> "Slow down slightly to confirm important details."
        SILENT_ACT -> "Minimize speech and prioritize action with terse acknowledgments."
    }

    fun tighten(): ResponseMode = when (this) {
        FAST -> NORMAL
        NORMAL -> FOCUSED
        FOCUSED -> SILENT_ACT
        SILENT_ACT -> SILENT_ACT
    }

    companion object {
        fun fromStorageValue(value: String?): ResponseMode {
            return when (value) {
                FAST.storageValue,
                "concise" -> FAST

                NORMAL.storageValue,
                "natural",
                null -> NORMAL

                FOCUSED.storageValue,
                "detailed" -> FOCUSED

                SILENT_ACT.storageValue -> SILENT_ACT
                else -> NORMAL
            }
        }
    }
}

enum class ConfirmationPolicy(val storageValue: String) {
    Standard("standard"),
    AlwaysConfirmOutbound("always_confirm_outbound"),
    Minimal("minimal");

    companion object {
        fun fromStorageValue(value: String?): ConfirmationPolicy {
            return when (value) {
                AlwaysConfirmOutbound.storageValue,
                "always" -> AlwaysConfirmOutbound

                Minimal.storageValue,
                "never" -> Minimal

                Standard.storageValue,
                "sensitive_only",
                null -> Standard

                else -> Standard
            }
        }
    }
}
