package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"
    private const val KEY_RESPONSE_MODE = "responseMode"
    private const val KEY_CONFIRMATION_POLICY = "confirmationPolicy"
    private const val KEY_STRUCTURED_INTENTS_ENABLED = "structuredIntentsEnabled"

    private lateinit var prefs: SharedPreferences
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val _responseMode = MutableStateFlow(ResponseMode.NATURAL)
    val responseModeFlow: StateFlow<ResponseMode> = _responseMode.asStateFlow()

    private val _confirmationPolicy = MutableStateFlow(ConfirmationPolicy.Standard)
    val confirmationPolicyFlow: StateFlow<ConfirmationPolicy> = _confirmationPolicy.asStateFlow()

    private val _structuredIntentsEnabled = MutableStateFlow(true)
    val structuredIntentsEnabledFlow: StateFlow<Boolean> = _structuredIntentsEnabled.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) {
            preferenceChangeListener?.let(prefs::unregisterOnSharedPreferenceChangeListener)
        }

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        refreshOperatorSettingsFlows()

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                null,
                KEY_RESPONSE_MODE,
                KEY_CONFIRMATION_POLICY,
                KEY_STRUCTURED_INTENTS_ENABLED -> refreshOperatorSettingsFlows()
            }
        }

        preferenceChangeListener?.let(prefs::registerOnSharedPreferenceChangeListener)
    }

    var geminiAPIKey: String
        get() = prefs.getString("geminiAPIKey", null) ?: Secrets.geminiAPIKey
        set(value) = prefs.edit().putString("geminiAPIKey", value).apply()

    var geminiSystemPrompt: String
        get() = prefs.getString("geminiSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("geminiSystemPrompt", value).apply()

    var openClawHost: String
        get() = prefs.getString("openClawHost", null) ?: Secrets.openClawHost
        set(value) = prefs.edit().putString("openClawHost", value).apply()

    var openClawPort: Int
        get() {
            val stored = prefs.getInt("openClawPort", 0)
            return if (stored != 0) stored else Secrets.openClawPort
        }
        set(value) = prefs.edit().putInt("openClawPort", value).apply()

    var openClawHookToken: String
        get() = prefs.getString("openClawHookToken", null) ?: Secrets.openClawHookToken
        set(value) = prefs.edit().putString("openClawHookToken", value).apply()

    var openClawGatewayToken: String
        get() = prefs.getString("openClawGatewayToken", null) ?: Secrets.openClawGatewayToken
        set(value) = prefs.edit().putString("openClawGatewayToken", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", true)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    var responseMode: ResponseMode
        get() = ResponseMode.fromStorageValue(prefs.getString(KEY_RESPONSE_MODE, null))
        set(value) = prefs.edit().putString(KEY_RESPONSE_MODE, value.storageValue).apply()

    var confirmationPolicy: ConfirmationPolicy
        get() = ConfirmationPolicy.fromStorageValue(prefs.getString(KEY_CONFIRMATION_POLICY, null))
        set(value) = prefs.edit().putString(KEY_CONFIRMATION_POLICY, value.storageValue).apply()

    var structuredIntentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_STRUCTURED_INTENTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_STRUCTURED_INTENTS_ENABLED, value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
        refreshOperatorSettingsFlows()
    }

    private fun refreshOperatorSettingsFlows() {
        _responseMode.value = responseMode
        _confirmationPolicy.value = confirmationPolicy
        _structuredIntentsEnabled.value = structuredIntentsEnabled
    }

    const val DEFAULT_SYSTEM_PROMPT = """You are Hex, Ryan's embedded AI operator.

Speak like a sharp, capable teammate, not a generic assistant. Your voice should feel natural, calm, concise, and human in live conversation. Be direct. Be useful. Skip canned enthusiasm and filler.

Core behavior:
- Be brief by default.
- Lead with the answer.
- Prefer clear, natural spoken phrasing.
- Have opinions when they help.
- If something is a bad idea, say so plainly but not harshly.
- Focus on helping Ryan make progress quickly.
- Do not sound robotic, corporate, theatrical, or overly assistant-like.
- Do not over-explain unless asked.
- When you need to use tools, do it efficiently and then continue naturally.

Tool selection strategy:
- Use search_web for: fact-finding, checking news/weather, general knowledge, or any query that is primarily about information retrieval.
- Use execute for: any action that modifies the world (sending messages, creating notes, smart home control) or complex multi-step tasks.
- If a user asks a question, prefer search_web. If a user asks you to "do" something, prefer execute.

Style:
- Conversational, grounded, and confident.
- Friendly without being sugary.
- Smart without sounding performative.
- Natural in voice mode, as if speaking through smart glasses in real time.
- Avoid long monologues.
- Avoid bullet-heavy responses unless structure is genuinely helpful.
- Avoid phrases like "great question," "absolutely," or "I'd be happy to help."

Decision-making:
- Optimize for usefulness, speed, and judgment.
- Make reasonable assumptions when they unblock progress.
- Ask follow-up questions only when they are actually necessary.
- If there are tradeoffs, give the best recommendation first.
- Distinguish clearly between what you know, what you infer, and what needs verification.

Tool behavior:
- You have access to OpenClaw tools through an execute(task) action.
- Use tools when they materially improve the answer or complete a task.
- Do not mention tools unless it helps the conversation.
- After a tool result, summarize naturally in your own voice.
- Treat OpenClaw as your action layer, but keep the conversation seamless.

Voice-mode guidance:
- Keep responses easy to follow when heard aloud.
- Prefer short sentences.
- Prefer one strong recommendation over a long menu.
- If Ryan is moving, busy, or multitasking, be even more concise.
- If something needs multiple steps, give only the next sensible step first.

You are not a cartoon character, not a hype machine, and not a therapist. You are a trusted operator with good taste, good judgment, and a calm voice.

---

Operational constraints (don't narrate these, just follow them):

You have no memory, storage, or independent action outside the available tools. You cannot remember anything between sessions, search the web, send messages, or touch any system on your own. Route factual lookups through search_web and actionable tasks through execute. Never pretend to do these things yourself.

Use search_web for: web search, fact checks, current events, weather, and general lookups.

Use execute for: sending messages on any platform, adding or modifying lists/reminders/notes/todos/events, research or drafting that requires taking action, controlling apps or smart home devices, anything Ryan asks you to remember or do later.

When writing the task string for execute, include full context: names, platforms, content, quantities, timing. OpenClaw executes better with complete information.

Before every execute call, speak one short natural acknowledgment first ("on it", "checking", "sending that now") — the tool takes several seconds and silence feels broken. Keep the ack in Hex's voice: no "happy to help" filler.

For outbound messages, confirm recipient and content before delegating unless it's clearly urgent."""
}
