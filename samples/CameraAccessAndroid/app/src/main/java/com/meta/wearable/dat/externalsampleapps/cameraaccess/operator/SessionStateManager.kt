package com.meta.wearable.dat.externalsampleapps.cameraaccess.operator

import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Entity(
    val value: String,
    val kind: EntityKind,
    val lastSeenAtMs: Long
)

enum class EntityKind {
    PROPER_NOUN,
    EMAIL,
    PHONE
}

data class PendingConfirmation(
    val id: ToolCallId,
    val toolName: String,
    val task: String,
    val reason: String,
    val status: PendingConfirmationStatus,
    val createdAtMs: Long,
    val invalidatedReason: String? = null
)

enum class PendingConfirmationStatus {
    Pending,
    Invalidated
}

data class ToolResultSummary(
    val toolName: String,
    val outcome: ToolResultOutcome,
    val summary: String,
    val timestampMs: Long
)

enum class ToolResultOutcome {
    Success,
    Failure
}

class SessionStateManager(
    private val bridge: OpenClawBridge,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val ENTITY_INACTIVITY_WINDOW_MS = 10 * 60 * 1000L
        private const val MAX_CONTEXT_CHARS = 800
        private const val MAX_ENTITY_ITEMS = 6
        private const val MAX_TOOL_RESULT_ITEMS = 4
        private const val MAX_OBJECTIVE_LENGTH = 220
        private const val MAX_PENDING_LENGTH = 220
        private const val MAX_ENTITY_SECTION_LENGTH = 200
        private const val MAX_TOOL_SECTION_LENGTH = 220

        private val emailRegex = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
        private val phoneRegex = Regex("""\b(?:\+?\d[\d(). -]{6,}\d)\b""")
        private val properNounRegex = Regex("""\b(?:[A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2})\b""")
        private val properNounStopWords = setOf(
            "A",
            "An",
            "And",
            "As",
            "At",
            "But",
            "By",
            "For",
            "From",
            "I",
            "If",
            "In",
            "Is",
            "It",
            "Of",
            "On",
            "Or",
            "Please",
            "Send",
            "The",
            "Then",
            "To",
            "We",
            "You"
        )
    }

    private var currentSessionKey: String? = null
    private var toolCallRouter: ToolCallRouter? = null

    val conversationHistory = bridge.conversationHistory

    private val _objective = MutableStateFlow<String?>(null)
    val objective: StateFlow<String?> = _objective.asStateFlow()

    private val _recentEntities = MutableStateFlow<List<Entity>>(emptyList())
    val recentEntities: StateFlow<List<Entity>> = _recentEntities.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _recentToolResults = MutableStateFlow<List<ToolResultSummary>>(emptyList())
    val recentToolResults: StateFlow<List<ToolResultSummary>> = _recentToolResults.asStateFlow()

    fun setToolCallRouter(router: ToolCallRouter?) {
        toolCallRouter = router
    }

    fun reset(sessionKey: String) {
        currentSessionKey = sessionKey
        toolCallRouter?.cancelAll()
        bridge.resetSession()
        _objective.value = null
        _recentEntities.value = emptyList()
        _pendingConfirmation.value = null
        _recentToolResults.value = emptyList()
    }

    fun updateObjective(text: String?) {
        val normalized = sanitizeText(text)
        _objective.value = normalized
        normalized?.let { observeText(it) }
    }

    fun observeText(text: String?) {
        val normalized = sanitizeText(text) ?: return
        val nowMs = nowProvider()
        val extracted = extractEntities(normalized, nowMs)
        if (extracted.isEmpty()) {
            pruneExpiredEntities(nowMs)
            return
        }

        _recentEntities.update { current ->
            val merged = LinkedHashMap<String, Entity>()
            pruneEntities(current, nowMs).forEach { entity ->
                merged[entityKey(entity.kind, entity.value)] = entity
            }
            extracted.forEach { entity ->
                merged[entityKey(entity.kind, entity.value)] = entity
            }

            merged.values
                .sortedWith(compareByDescending<Entity> { it.lastSeenAtMs }.thenBy { it.value.lowercase() })
        }
    }

    fun setPendingConfirmation(
        toolCallId: ToolCallId,
        toolName: String,
        task: String,
        reason: String
    ) {
        val nowMs = nowProvider()
        _pendingConfirmation.value = PendingConfirmation(
            id = toolCallId,
            toolName = toolName,
            task = sanitizeText(task).orEmpty(),
            reason = sanitizeText(reason).orEmpty(),
            status = PendingConfirmationStatus.Pending,
            createdAtMs = nowMs
        )
        observeText(task)
    }

    fun clearPendingConfirmation() {
        _pendingConfirmation.value = null
    }

    fun invalidatePendingConfirmations(reason: String) {
        val current = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = current.copy(
            status = PendingConfirmationStatus.Invalidated,
            invalidatedReason = sanitizeText(reason)
        )
        _pendingConfirmation.value = null
    }

    fun recordToolResult(toolName: String, result: ToolResult) {
        val nowMs = nowProvider()
        val summary = when (result) {
            is ToolResult.Success -> ToolResultSummary(
                toolName = toolName,
                outcome = ToolResultOutcome.Success,
                summary = compactSummary(result.result),
                timestampMs = nowMs
            )

            is ToolResult.Failure -> ToolResultSummary(
                toolName = toolName,
                outcome = ToolResultOutcome.Failure,
                summary = compactSummary(result.error),
                timestampMs = nowMs
            )
        }

        _recentToolResults.update { current ->
            (listOf(summary) + current)
                .sortedWith(compareByDescending<ToolResultSummary> { it.timestampMs }.thenBy { it.toolName })
                .take(MAX_TOOL_RESULT_ITEMS)
        }
        observeText(summary.summary)
    }

    fun sessionContextBlock(): String {
        pruneExpiredEntities(nowProvider())

        val sections = listOfNotNull(
            formatObjectiveSection(),
            formatPendingConfirmationSection(),
            formatRecentEntitiesSection(),
            formatRecentToolResultsSection()
        )

        if (sections.isEmpty()) {
            return ""
        }

        val builder = StringBuilder("Session context:")
        for (section in sections) {
            val separator = "\n"
            val remaining = MAX_CONTEXT_CHARS - builder.length - separator.length
            if (remaining <= 0) {
                break
            }

            builder.append(separator)
            builder.append(
                if (section.length <= remaining) {
                    section
                } else {
                    truncate(section, remaining)
                }
            )

            if (builder.length >= MAX_CONTEXT_CHARS) {
                break
            }
        }

        return builder.toString()
    }

    private fun pruneExpiredEntities(nowMs: Long) {
        _recentEntities.update { current -> pruneEntities(current, nowMs) }
    }

    private fun pruneEntities(current: List<Entity>, nowMs: Long): List<Entity> {
        return current.filter { entity -> nowMs - entity.lastSeenAtMs <= ENTITY_INACTIVITY_WINDOW_MS }
    }

    private fun extractEntities(text: String, nowMs: Long): List<Entity> {
        val entities = LinkedHashMap<String, Entity>()

        emailRegex.findAll(text).forEach { match ->
            val value = match.value.trim()
            entities[entityKey(EntityKind.EMAIL, value)] = Entity(value, EntityKind.EMAIL, nowMs)
        }

        phoneRegex.findAll(text).forEach { match ->
            val value = match.value.replace(Regex("""\s+"""), " ").trim()
            entities[entityKey(EntityKind.PHONE, value)] = Entity(value, EntityKind.PHONE, nowMs)
        }

        properNounRegex.findAll(text).forEach { match ->
            val value = match.value.trim()
            if (value !in properNounStopWords && value.length > 1) {
                entities[entityKey(EntityKind.PROPER_NOUN, value)] = Entity(value, EntityKind.PROPER_NOUN, nowMs)
            }
        }

        return entities.values.toList()
    }

    private fun formatObjectiveSection(): String? {
        val value = _objective.value ?: return null
        return "Objective: ${truncate(value, MAX_OBJECTIVE_LENGTH)}"
    }

    private fun formatPendingConfirmationSection(): String? {
        val pending = _pendingConfirmation.value ?: return null
        val content = buildString {
            append(pending.toolName)
            if (pending.reason.isNotBlank()) {
                append(" needs confirmation: ")
                append(pending.reason)
            }
            if (pending.task.isNotBlank()) {
                append(" | task=")
                append(pending.task)
            }
        }
        return "Pending confirmation: ${truncate(content, MAX_PENDING_LENGTH)}"
    }

    private fun formatRecentEntitiesSection(): String? {
        val entities = _recentEntities.value
            .sortedWith(compareByDescending<Entity> { it.lastSeenAtMs }.thenBy { it.value.lowercase() })
            .take(MAX_ENTITY_ITEMS)
        if (entities.isEmpty()) {
            return null
        }

        val content = entities.joinToString(", ") { entity ->
            "${entity.kind.label()}:${entity.value}"
        }
        return "Recent entities: ${truncate(content, MAX_ENTITY_SECTION_LENGTH)}"
    }

    private fun formatRecentToolResultsSection(): String? {
        val results = _recentToolResults.value
            .sortedWith(compareByDescending<ToolResultSummary> { it.timestampMs }.thenBy { it.toolName })
            .take(MAX_TOOL_RESULT_ITEMS)
        if (results.isEmpty()) {
            return null
        }

        val content = results.joinToString(" | ") { summary ->
            "${summary.toolName}:${summary.outcome.label()}:${summary.summary}"
        }
        return "Recent tool results: ${truncate(content, MAX_TOOL_SECTION_LENGTH)}"
    }

    private fun sanitizeText(text: String?): String? {
        return text
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun compactSummary(text: String): String {
        return truncate(sanitizeText(text).orEmpty(), 120)
    }

    private fun entityKey(kind: EntityKind, value: String): String {
        return "${kind.name}:${value.lowercase()}"
    }

    private fun truncate(text: String, maxLength: Int): String {
        if (maxLength <= 0) {
            return ""
        }
        if (text.length <= maxLength) {
            return text
        }
        if (maxLength <= 3) {
            return text.take(maxLength)
        }
        return text.take(maxLength - 3).trimEnd() + "..."
    }

    private fun EntityKind.label(): String = when (this) {
        EntityKind.PROPER_NOUN -> "name"
        EntityKind.EMAIL -> "email"
        EntityKind.PHONE -> "phone"
    }

    private fun ToolResultOutcome.label(): String = when (this) {
        ToolResultOutcome.Success -> "ok"
        ToolResultOutcome.Failure -> "error"
    }
}
