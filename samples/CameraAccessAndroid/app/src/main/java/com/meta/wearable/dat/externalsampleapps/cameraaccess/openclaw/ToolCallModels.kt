package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import org.json.JSONArray
import org.json.JSONObject

// Gemini Tool Call (parsed from server JSON)

data class GeminiFunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

data class GeminiToolCall(
    val functionCalls: List<GeminiFunctionCall>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCall? {
            val toolCall = json.optJSONObject("toolCall") ?: return null
            val calls = toolCall.optJSONArray("functionCalls") ?: return null
            val functionCalls = mutableListOf<GeminiFunctionCall>()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id", "")
                val name = call.optString("name", "")
                if (id.isEmpty() || name.isEmpty()) continue
                val argsObj = call.optJSONObject("args")
                val args = mutableMapOf<String, Any?>()
                if (argsObj != null) {
                    for (key in argsObj.keys()) {
                        args[key] = argsObj.opt(key)
                    }
                }
                functionCalls.add(GeminiFunctionCall(id, name, args))
            }
            return if (functionCalls.isNotEmpty()) GeminiToolCall(functionCalls) else null
        }
    }
}

// Gemini Tool Call Cancellation

data class GeminiToolCallCancellation(
    val ids: List<String>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCallCancellation? {
            val cancellation = json.optJSONObject("toolCallCancellation") ?: return null
            val idsArray = cancellation.optJSONArray("ids") ?: return null
            val ids = mutableListOf<String>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getString(i))
            }
            return if (ids.isNotEmpty()) GeminiToolCallCancellation(ids) else null
        }
    }
}

// Tool Result

sealed class ToolResult {
    data class Success(val result: String) : ToolResult()
    data class Failure(
        val error: String,
        val hint: String? = null
    ) : ToolResult()

    fun toJSON(): JSONObject = when (this) {
        is Success -> JSONObject().put("result", result)
        is Failure -> JSONObject().apply {
            put("error", error)
            hint?.let { put("hint", it) }
        }
    }
}

// Tool Call Status (for UI)

sealed class ToolCallStatus {
    data object Idle : ToolCallStatus()
    data class Executing(val name: String) : ToolCallStatus()
    data class Completed(val name: String) : ToolCallStatus()
    data class Failed(val name: String, val error: String) : ToolCallStatus()
    data class Cancelled(val name: String) : ToolCallStatus()

    val displayText: String
        get() = when (this) {
            is Idle -> ""
            is Executing -> "Running: $name..."
            is Completed -> "Done: $name"
            is Failed -> "Failed: $name - $error"
            is Cancelled -> "Cancelled: $name"
        }

    val isActive: Boolean
        get() = this is Executing
}

// OpenClaw Connection State

sealed class OpenClawConnectionState {
    data object NotConfigured : OpenClawConnectionState()
    data object Checking : OpenClawConnectionState()
    data object Connected : OpenClawConnectionState()
    data class Unreachable(val message: String) : OpenClawConnectionState()
}

// Tool Declarations (for Gemini setup message)

object ToolDeclarations {
    fun allDeclarationsJSON(): JSONArray {
        return JSONArray().apply {
            put(executeJSON())
            put(searchWebJSON())
            put(confirmPendingJSON())
            put(sendMessageJSON())
            put(setReminderJSON())
            put(captureTaskJSON())
        }
    }

    private fun executeJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "execute")
            put("description", "Your way to take action for complex tasks. Use this tool for: sending messages, adding to lists, setting reminders, creating notes, drafts, scheduling, smart home control, app interactions, or any request that requires multi-step logic or specific app integrations. DO NOT use this for simple fact-finding or general web searches.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("task", JSONObject().apply {
                        put("type", "string")
                        put("description", "Clear, detailed description of what to do. Include all relevant context: names, content, platforms, quantities, etc.")
                    })
                })
                put("required", JSONArray().put("task"))
            })
            put("behavior", "BLOCKING")
        }
    }

    private fun searchWebJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "search_web")
            put("description", "The primary tool for fact-finding, research, and general information retrieval. Use this whenever you need to look something up on the web, verify a fact, or gather information about the world. Use execute only for actions that change the world or require app integrations.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The search query to look up. Be specific for better results.")
                    })
                    put("hint", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional hint field used on rejection to explain why a specific search was requested.")
                    })
                })
                put("required", JSONArray().put("query"))
            })
            put("behavior", "BLOCKING")
        }
    }

    fun confirmPendingJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "confirm_pending")
            put("description", "Use this only after a prior tool response tells you an action is waiting for confirmation. Pass the exact pendingActionId from that response and set confirm=true to proceed or confirm=false to cancel it.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("pendingActionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "The pendingActionId returned by the earlier confirmation-required tool response.")
                    })
                    put("confirm", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "true to approve the pending action, false to cancel it.")
                    })
                })
                put("required", JSONArray().put("pendingActionId").put("confirm"))
            })
            put("behavior", "BLOCKING")
        }
    }

    private fun sendMessageJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "send_message")
            put("description", "Send a short message when the recipient and content are already known. Use execute for broader messaging workflows or when you need app-specific logic.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().apply {
                        put("type", "string")
                        put("description", "Who should receive the message.")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Exact message body to send.")
                    })
                    put("channel", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("sms").put("chat").put("email"))
                        put("description", "Optional delivery channel. Omit when the user did not specify one.")
                    })
                })
                put("required", JSONArray().put("recipient").put("content"))
            })
            put("behavior", "BLOCKING")
        }
    }

    private fun setReminderJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "set_reminder")
            put("description", "Create a reminder from a natural-language time and reminder text.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("when", JSONObject().apply {
                        put("type", "string")
                        put("description", "Natural-language time such as tomorrow at 3pm.")
                    })
                    put("what", JSONObject().apply {
                        put("type", "string")
                        put("description", "What the reminder should say or accomplish.")
                    })
                })
                put("required", JSONArray().put("when").put("what"))
            })
            put("behavior", "BLOCKING")
        }
    }

    private fun captureTaskJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "capture_task")
            put("description", "Capture a to-do item or lightweight task without requiring a follow-up confirmation.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "Short task title.")
                    })
                    put("priority", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("low").put("med").put("high"))
                        put("description", "Optional task priority.")
                    })
                    put("notes", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional supporting notes for the task.")
                    })
                })
                put("required", JSONArray().put("title"))
            })
            put("behavior", "BLOCKING")
        }
    }
}
