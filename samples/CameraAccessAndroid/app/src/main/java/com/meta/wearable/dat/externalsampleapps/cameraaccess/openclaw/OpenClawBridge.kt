package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.operator.ToolCallId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

open class OpenClawBridge {
    companion object {
        private const val TAG = "OpenClawBridge"
        private const val MAX_HISTORY_TURNS = 10
    }

    private val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _toolCallStates = MutableStateFlow<Map<ToolCallId, ToolCallState>>(emptyMap())
    val toolCallStates: StateFlow<Map<ToolCallId, ToolCallState>> = _toolCallStates.asStateFlow()
    val lastToolCallStatus: StateFlow<ToolCallStatus> = toolCallStates
        .map(Map<ToolCallId, ToolCallState>::latestStatus)
        .stateIn(flowScope, SharingStarted.Eagerly, ToolCallStatus.Idle)

    private val _connectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.NotConfigured)
    val connectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()

    fun setToolCallState(callId: ToolCallId, status: ToolCallStatus) {
        _toolCallStates.update { current ->
            current + (callId to ToolCallState(status = status))
        }
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private var sessionKey: String = "agent:main:glass"
    private val conversationHistory = mutableListOf<JSONObject>()
    val operatorSessionId: String
        get() = sessionKey

    suspend fun checkConnection() = withContext(Dispatchers.IO) {
        if (!GeminiConfig.isOpenClawConfigured) {
            _connectionState.value = OpenClawConnectionState.NotConfigured
            return@withContext
        }
        _connectionState.value = OpenClawConnectionState.Checking

        val url = "${GeminiConfig.openClawHost}:${GeminiConfig.openClawPort}/v1/chat/completions"
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer ${GeminiConfig.openClawGatewayToken}")
                .addHeader("x-openclaw-message-channel", "glass")
                .build()

            val response = pingClient.newCall(request).execute()
            val code = response.code
            response.close()

            if (code in 200..499) {
                _connectionState.value = OpenClawConnectionState.Connected
                Log.d(TAG, "Gateway reachable (HTTP $code)")
            } else {
                _connectionState.value = OpenClawConnectionState.Unreachable("Unexpected response")
            }
        } catch (e: Exception) {
            _connectionState.value = OpenClawConnectionState.Unreachable(e.message ?: "Unknown error")
            Log.d(TAG, "Gateway unreachable: ${e.message}")
        }
    }

    fun resetSession() {
        conversationHistory.clear()
        Log.d(TAG, "Session reset (key retained: $sessionKey)")
    }

    fun shutdown() {
        flowScope.cancel()
    }

    open suspend fun delegateTask(
        callId: ToolCallId,
        task: String,
        toolName: String = "execute"
    ): ToolResult = withContext(Dispatchers.IO) {
        setToolCallState(callId, ToolCallStatus.Executing(toolName))

        val url = "${GeminiConfig.openClawHost}:${GeminiConfig.openClawPort}/v1/chat/completions"

        // Append user message
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", task)
        })

        // Trim history
        if (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
            val trimmed = conversationHistory.takeLast(MAX_HISTORY_TURNS * 2)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }

        Log.d(TAG, "Sending ${conversationHistory.size} messages in conversation")

        try {
            val messagesArray = JSONArray()
            for (msg in conversationHistory) {
                messagesArray.put(msg)
            }

            val body = JSONObject().apply {
                put("model", "openclaw")
                put("messages", messagesArray)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${GeminiConfig.openClawGatewayToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-openclaw-session-key", sessionKey)
                .addHeader("x-openclaw-message-channel", "glass")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            if (statusCode !in 200..299) {
                Log.d(TAG, "Chat failed: HTTP $statusCode - ${responseBody.take(200)}")
                setToolCallState(callId, ToolCallStatus.Failed(toolName, "HTTP $statusCode"))
                return@withContext ToolResult.Failure("Agent returned HTTP $statusCode")
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val content = choices?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")

            if (!content.isNullOrEmpty()) {
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                Log.d(TAG, "Agent result: ${content.take(200)}")
                setToolCallState(callId, ToolCallStatus.Completed(toolName))
                return@withContext ToolResult.Success(content)
            }

            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", responseBody)
            })
            Log.d(TAG, "Agent raw: ${responseBody.take(200)}")
            setToolCallState(callId, ToolCallStatus.Completed(toolName))
            return@withContext ToolResult.Success(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Agent error: ${e.message}")
            setToolCallState(callId, ToolCallStatus.Failed(toolName, e.message ?: "Unknown"))
            return@withContext ToolResult.Failure("Agent error: ${e.message}")
        }
    }

}
