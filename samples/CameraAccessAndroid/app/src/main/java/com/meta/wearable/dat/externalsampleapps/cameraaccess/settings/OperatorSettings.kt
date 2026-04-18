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
    NEVER("never"),
    SENSITIVE_ONLY("sensitive_only"),
    ALWAYS("always");

    companion object {
        fun fromStorageValue(value: String?): ConfirmationPolicy {
            return entries.firstOrNull { it.storageValue == value } ?: NEVER
        }
    }
}
