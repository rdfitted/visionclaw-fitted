package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.Entity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.EntityKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager

internal data class ValidationIssue(
    val error: String,
    val hint: String
)

internal enum class MessageChannel(
    val wireValue: String,
    val taskLabel: String
) {
    SMS("sms", "SMS"),
    CHAT("chat", "chat message"),
    EMAIL("email", "email");

    companion object {
        fun fromRaw(raw: String?): MessageChannel? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) {
                return null
            }
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

internal data class SendMessagePayload(
    val recipient: String,
    val content: String,
    val channel: MessageChannel?
)

internal data class SendMessageAssessment(
    val payload: SendMessagePayload,
    val isSensitive: Boolean,
    val isNewRecipient: Boolean,
    val isGroupOrAmbiguous: Boolean,
    val isFrequentContactShortMessage: Boolean
)

internal data class SetReminderPayload(
    val whenText: String,
    val what: String
)

internal enum class TaskPriority(
    val wireValue: String,
    val taskLabel: String
) {
    LOW("low", "low"),
    MED("med", "medium"),
    HIGH("high", "high");

    companion object {
        fun fromRaw(raw: String?): TaskPriority? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) {
                return null
            }
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

internal data class CaptureTaskPayload(
    val title: String,
    val priority: TaskPriority?,
    val notes: String?
)

internal object StructuredToolPayloads {
    internal const val OPERATOR_UNDO_KEY = "_operatorUndo"

    private const val FAST_MESSAGE_MAX_CHARS = 120
    private const val FAST_MESSAGE_MAX_WORDS = 18

    private val whitespaceRegex = Regex("""\s+""")
    private val taskLineBreakRegex = Regex("""[\r\n]+""")
    private val sensitiveContentRegex = Regex(
        """\b(password|passcode|pin|otp|one[- ]time code|verification code|ssn|social security|routing number|account number|bank account|credit card|debit card|cvv|cvc|wire transfer|crypto wallet|seed phrase|tax return|tax document)\b""",
        RegexOption.IGNORE_CASE
    )
    private val groupRecipientRegex = Regex(
        """[,/&+]|\b(and|team|group|everyone|everybody|all|folks|channel|thread|room)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ambiguousRecipientRegex = Regex(
        """\b(him|her|them|that person|that guy|that girl|somebody|someone)\b""",
        RegexOption.IGNORE_CASE
    )

    fun validateSendMessageArgs(args: Map<String, Any?>): ValidationIssue? {
        val recipient = (args["recipient"] as? String)?.trim().orEmpty()
        if (recipient.isEmpty()) {
            return ValidationIssue(
                error = "Missing recipient argument",
                hint = "Provide a non-empty recipient."
            )
        }

        val content = (args["content"] as? String)?.trim().orEmpty()
        if (content.isEmpty()) {
            return ValidationIssue(
                error = "Missing content argument",
                hint = "Provide a non-empty message body."
            )
        }

        val rawChannel = (args["channel"] as? String)?.trim()
        if (!rawChannel.isNullOrEmpty() && MessageChannel.fromRaw(rawChannel) == null) {
            return ValidationIssue(
                error = "Invalid channel argument",
                hint = "Channel must be one of sms, chat, or email."
            )
        }

        return null
    }

    fun parseSendMessagePayload(args: Map<String, Any?>): SendMessagePayload? {
        if (validateSendMessageArgs(args) != null) {
            return null
        }

        return SendMessagePayload(
            recipient = (args["recipient"] as String).trim(),
            content = (args["content"] as String).trim(),
            channel = MessageChannel.fromRaw(args["channel"] as? String)
        )
    }

    fun assessSendMessage(
        payload: SendMessagePayload,
        sessionStateManager: SessionStateManager
    ): SendMessageAssessment {
        val knownRecipient = sessionStateManager.recentEntities.value.any { entity ->
            recipientMatches(payload.recipient, entity)
        }
        val isSensitive = sensitiveContentRegex.containsMatchIn(payload.content)
        val isGroupOrAmbiguous =
            groupRecipientRegex.containsMatchIn(payload.recipient) ||
                ambiguousRecipientRegex.containsMatchIn(payload.recipient)
        val wordCount = payload.content
            .split(whitespaceRegex)
            .count { token -> token.isNotBlank() }
        val isFrequentContactShortMessage =
            knownRecipient &&
                !isSensitive &&
                !isGroupOrAmbiguous &&
                payload.content.length <= FAST_MESSAGE_MAX_CHARS &&
                wordCount <= FAST_MESSAGE_MAX_WORDS

        return SendMessageAssessment(
            payload = payload,
            isSensitive = isSensitive,
            isNewRecipient = !knownRecipient,
            isGroupOrAmbiguous = isGroupOrAmbiguous,
            isFrequentContactShortMessage = isFrequentContactShortMessage
        )
    }

    fun buildSendMessageTask(payload: SendMessagePayload): String {
        val recipient = escapeTaskLiteral(payload.recipient)
        val content = escapeTaskLiteral(payload.content)
        return when (payload.channel) {
            MessageChannel.EMAIL ->
                "Send an email to $recipient with this content: \"$content\"."
            MessageChannel.SMS ->
                "Send an SMS to $recipient: \"$content\"."
            MessageChannel.CHAT ->
                "Send a chat message to $recipient: \"$content\"."
            null ->
                "Send a message to $recipient: \"$content\"."
        }
    }

    fun buildSendMessagePrompt(payload: SendMessagePayload): String {
        val channel = payload.channel?.taskLabel ?: "message"
        return "Confirm sending this $channel to ${payload.recipient}."
    }

    fun validateSetReminderArgs(args: Map<String, Any?>): ValidationIssue? {
        val whenText = (args["when"] as? String)?.trim().orEmpty()
        if (whenText.isEmpty()) {
            return ValidationIssue(
                error = "Missing when argument",
                hint = "Provide a non-empty natural-language time like \"tomorrow at 3pm\"."
            )
        }

        val what = (args["what"] as? String)?.trim().orEmpty()
        if (what.isEmpty()) {
            return ValidationIssue(
                error = "Missing what argument",
                hint = "Provide what the reminder should be about."
            )
        }

        return null
    }

    fun parseSetReminderPayload(args: Map<String, Any?>): SetReminderPayload? {
        if (validateSetReminderArgs(args) != null) {
            return null
        }

        return SetReminderPayload(
            whenText = (args["when"] as String).trim(),
            what = (args["what"] as String).trim()
        )
    }

    fun buildSetReminderTask(payload: SetReminderPayload): String {
        val whenText = escapeTaskLiteral(payload.whenText)
        val what = escapeTaskLiteral(payload.what)
        return "Set a reminder for \"$whenText\" to $what."
    }

    fun buildSetReminderUndoPayload(payload: SetReminderPayload): Map<String, Any?> {
        val whenText = escapeTaskLiteral(payload.whenText)
        val what = escapeTaskLiteral(payload.what)
        return mapOf(
            "task" to "Cancel the reminder \"$what\" scheduled for \"$whenText\".",
            OPERATOR_UNDO_KEY to true
        )
    }

    fun validateCaptureTaskArgs(args: Map<String, Any?>): ValidationIssue? {
        val title = (args["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) {
            return ValidationIssue(
                error = "Missing title argument",
                hint = "Provide a short title for the task."
            )
        }

        val rawPriority = (args["priority"] as? String)?.trim()
        if (!rawPriority.isNullOrEmpty() && TaskPriority.fromRaw(rawPriority) == null) {
            return ValidationIssue(
                error = "Invalid priority argument",
                hint = "Priority must be one of low, med, or high."
            )
        }

        return null
    }

    fun parseCaptureTaskPayload(args: Map<String, Any?>): CaptureTaskPayload? {
        if (validateCaptureTaskArgs(args) != null) {
            return null
        }

        return CaptureTaskPayload(
            title = (args["title"] as String).trim(),
            priority = TaskPriority.fromRaw(args["priority"] as? String),
            notes = (args["notes"] as? String)?.trim()?.takeIf(String::isNotEmpty)
        )
    }

    fun buildCaptureTaskTask(payload: CaptureTaskPayload): String {
        val title = escapeTaskLiteral(payload.title)
        val details = buildList {
            add("Capture a task titled \"$title\".")
            payload.priority?.let { priority ->
                add("Set priority to ${priority.taskLabel}.")
            }
            payload.notes?.let { notes ->
                add("Notes: ${escapeTaskLiteral(notes)}.")
            }
        }
        return details.joinToString(" ")
    }

    fun buildCaptureTaskUndoPayload(payload: CaptureTaskPayload): Map<String, Any?> {
        val title = escapeTaskLiteral(payload.title)
        val task = buildString {
            append("Delete the task titled \"$title\".")
            payload.notes?.let { notes ->
                append(" Notes to match: ${escapeTaskLiteral(notes)}.")
            }
        }
        return mapOf(
            "task" to task,
            OPERATOR_UNDO_KEY to true
        )
    }

    fun buildFallbackCall(call: GeminiFunctionCall): GeminiFunctionCall? {
        val task = extractStructuredTask(call) ?: return null
        return call.copy(args = call.args + ("task" to task))
    }

    fun extractStructuredTask(call: GeminiFunctionCall): String? {
        return when (call.name) {
            "send_message" -> parseSendMessagePayload(call.args)?.let(::buildSendMessageTask)
            "set_reminder" -> parseSetReminderPayload(call.args)?.let(::buildSetReminderTask)
            "capture_task" -> parseCaptureTaskPayload(call.args)?.let(::buildCaptureTaskTask)
            else -> null
        }
    }

    private fun escapeTaskLiteral(input: String): String {
        return input
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(taskLineBreakRegex, " ")
    }

    private fun recipientMatches(recipient: String, entity: Entity): Boolean {
        return when (entity.kind) {
            EntityKind.EMAIL -> recipient.trim().equals(entity.value, ignoreCase = true)
            EntityKind.PHONE -> {
                val recipientDigits = recipient.filter(Char::isDigit)
                val entityDigits = entity.value.filter(Char::isDigit)
                recipientDigits.isNotEmpty() &&
                    entityDigits.isNotEmpty() &&
                    (recipientDigits.endsWith(entityDigits) || entityDigits.endsWith(recipientDigits))
            }
            EntityKind.PROPER_NOUN -> {
                val recipientTokens = recipient
                    .trim()
                    .lowercase()
                    .split(whitespaceRegex)
                    .filter(String::isNotBlank)
                    .toSet()
                val entityTokens = entity.value
                    .trim()
                    .lowercase()
                    .split(whitespaceRegex)
                    .filter(String::isNotBlank)
                    .toSet()
                recipientTokens.isNotEmpty() &&
                    entityTokens.isNotEmpty() &&
                    (
                        recipientTokens == entityTokens ||
                            recipientTokens.containsAll(entityTokens) ||
                            entityTokens.containsAll(recipientTokens)
                        )
            }
        }
    }
}
