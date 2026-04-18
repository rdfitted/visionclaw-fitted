package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

enum class ResponseMode(val storageValue: String) {
    NATURAL("natural"),
    CONCISE("concise"),
    DETAILED("detailed");

    companion object {
        fun fromStorageValue(value: String?): ResponseMode {
            return entries.firstOrNull { it.storageValue == value } ?: NATURAL
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
