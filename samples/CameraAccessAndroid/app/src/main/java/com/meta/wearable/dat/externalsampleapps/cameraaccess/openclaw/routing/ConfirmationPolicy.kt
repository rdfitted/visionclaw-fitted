package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.routing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.Entity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.EntityKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.SessionStateManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.ConfirmationPolicy as OperatorConfirmationPolicy
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object ConfirmationPolicy {
    sealed class Tier {
        data object AlwaysConfirm : Tier()
        data class ConditionalConfirm(val prompt: String) : Tier()
        data object Implicit : Tier()
    }

    fun evaluate(intent: GeminiFunctionCall, sessionState: SessionStateManager): Tier {
        val intentType = IntentType.from(intent)
        return when (SettingsManager.confirmationPolicyFlow.value) {
            OperatorConfirmationPolicy.Minimal -> Tier.Implicit
            OperatorConfirmationPolicy.Standard -> intentType.standardTier(intent, sessionState)
            OperatorConfirmationPolicy.AlwaysConfirmOutbound ->
                intentType.alwaysOutboundTier(intent, sessionState)
        }
    }

    private enum class IntentType {
        Search,
        ConfirmPending,
        SendMessage,
        SetReminder,
        CaptureTask,
        OutboundMessage,
        Transactional,
        Destructive,
        DeviceControl,
        GeneralExecute,
        UndoCompensation;

        fun standardTier(intent: GeminiFunctionCall, sessionState: SessionStateManager): Tier = when (this) {
            Search,
            ConfirmPending,
            SetReminder,
            CaptureTask,
            GeneralExecute,
            UndoCompensation -> Tier.Implicit

            SendMessage -> sendMessageTier(intent, sessionState)
            OutboundMessage,
            Transactional,
            Destructive,
            DeviceControl -> Tier.ConditionalConfirm(prompt(sessionState))
        }

        fun alwaysOutboundTier(intent: GeminiFunctionCall, sessionState: SessionStateManager): Tier = when (this) {
            OutboundMessage,
            SendMessage -> Tier.AlwaysConfirm
            else -> standardTier(intent, sessionState)
        }

        private fun prompt(sessionState: SessionStateManager): String = when (this) {
            Search -> ""
            ConfirmPending -> ""
            SendMessage -> ""
            SetReminder -> ""
            CaptureTask -> ""
            OutboundMessage -> {
                val recipient = sessionState.bestRecentRecipient()
                if (recipient != null) {
                    "Confirm sending this to $recipient."
                } else {
                    "Confirm the recipient and exact message before sending."
                }
            }
            Transactional ->
                "Confirm the item, merchant, amount, or reservation details before proceeding."
            Destructive ->
                "Confirm before deleting, canceling, or changing existing data."
            DeviceControl ->
                "Confirm the device or automation change before proceeding."
            GeneralExecute ->
                "Confirm before taking this action."
            UndoCompensation ->
                ""
        }

        private fun sendMessageTier(
            intent: GeminiFunctionCall,
            sessionState: SessionStateManager
        ): Tier {
            val payload = StructuredToolPayloads.parseSendMessagePayload(intent.args)
                ?: return Tier.AlwaysConfirm
            val assessment = StructuredToolPayloads.assessSendMessage(payload, sessionState)
            return when {
                assessment.isSensitive || assessment.isNewRecipient -> Tier.AlwaysConfirm
                assessment.isGroupOrAmbiguous ->
                    Tier.ConditionalConfirm(StructuredToolPayloads.buildSendMessagePrompt(payload))
                assessment.isFrequentContactShortMessage -> Tier.Implicit
                else -> Tier.ConditionalConfirm(StructuredToolPayloads.buildSendMessagePrompt(payload))
            }
        }

        companion object {
            private val outboundMessageRegex = Regex(
                """\b(send|text|message|email|dm|reply|respond|post|publish|share)\b""",
                RegexOption.IGNORE_CASE
            )
            private val transactionalRegex = Regex(
                """\b(buy|purchase|order|book|reserve|pay|charge|transfer|donate)\b""",
                RegexOption.IGNORE_CASE
            )
            private val destructiveRegex = Regex(
                """\b(delete|remove|cancel|archive|unsubscribe|clear|wipe)\b""",
                RegexOption.IGNORE_CASE
            )
            private val deviceControlRegex = Regex(
                """\b(turn on|turn off|lock|unlock|open|close|arm|disarm|start|stop)\b""",
                RegexOption.IGNORE_CASE
            )

            fun from(intent: GeminiFunctionCall): IntentType = when {
                intent.args[StructuredToolPayloads.OPERATOR_UNDO_KEY] == true -> UndoCompensation
                intent.name == "search_web" -> Search
                intent.name == "confirm_pending" -> ConfirmPending
                intent.name == "send_message" -> SendMessage
                intent.name == "set_reminder" -> SetReminder
                intent.name == "capture_task" -> CaptureTask
                else -> fromTask(
                    (intent.args["task"] as? String)
                        ?.takeIf(String::isNotBlank)
                        ?: (intent.args["query"] as? String).orEmpty()
                )
            }

            private fun fromTask(task: String): IntentType = when {
                outboundMessageRegex.containsMatchIn(task) -> OutboundMessage
                transactionalRegex.containsMatchIn(task) -> Transactional
                destructiveRegex.containsMatchIn(task) -> Destructive
                deviceControlRegex.containsMatchIn(task) -> DeviceControl
                else -> GeneralExecute
            }
        }
    }

    private fun SessionStateManager.bestRecentRecipient(): String? {
        return recentEntities.value
            .asSequence()
            .sortedByDescending(Entity::lastSeenAtMs)
            .firstOrNull { entity -> entity.kind == EntityKind.EMAIL || entity.kind == EntityKind.PROPER_NOUN }
            ?.value
    }
}
