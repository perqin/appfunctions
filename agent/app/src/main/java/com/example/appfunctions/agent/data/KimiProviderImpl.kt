/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.data

import android.util.Log
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.domain.LlmInput
import com.example.appfunctions.agent.domain.LlmProvider
import com.example.appfunctions.agent.domain.LlmResponse
import com.example.appfunctions.agent.domain.LlmResponsePart
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Kimi Code provider using its OpenAI-compatible Chat Completions API. */
@Singleton
class KimiProviderImpl
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val toolConverter: GeminiToolConverter,
    ) : LlmProvider {
        override suspend fun generateResponse(
            previousInteractionId: String?,
            input: LlmInput,
            tools: List<AppFunctionMetadata>,
            apiKey: String,
            modelName: String,
            serviceTier: ServiceTier,
        ): LlmResponse {
            val previousMessages =
                try {
                    decodePreviousMessages(previousInteractionId)
                } catch (e: Exception) {
                    return LlmResponse.Error("Invalid Kimi conversation state: ${e.message}")
                }
            val messages = JsonArray(previousMessages + input.toMessages())
            val convertedTools =
                try {
                    convertTools(tools)
                } catch (e: IllegalArgumentException) {
                    return LlmResponse.Error(e.message ?: "Failed to convert tools for Kimi")
                }
            val requestBody =
                buildJsonObject {
                    put(KEY_MODEL, modelName)
                    put(KEY_MESSAGES, messages)
                    if (convertedTools.isNotEmpty()) {
                        put(KEY_TOOLS, convertedTools)
                    }
                }

            val response =
                try {
                    httpClient.post(KIMI_CHAT_COMPLETIONS_URL) {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        setBody(requestBody)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return LlmResponse.Error("Network error: ${e.message}")
                }
            val responseBodyText = response.bodyAsText()
            if (response.status.value !in 200..299) {
                return LlmResponse.Error("Kimi API error: ${response.status} - $responseBodyText")
            }

            return try {
                val responseBody = Json.parseToJsonElement(responseBodyText).jsonObject
                val choice =
                    responseBody[KEY_CHOICES]?.jsonArray?.firstOrNull()?.jsonObject
                        ?: return LlmResponse.Error("Kimi response did not contain a choice")
                val finishReason = choice[KEY_FINISH_REASON]?.jsonPrimitive?.contentOrNull
                if (finishReason == VALUE_LENGTH) {
                    return LlmResponse.Error("Kimi response was truncated because the token limit was reached")
                }
                val message =
                    choice[KEY_MESSAGE]?.jsonObject
                        ?: return LlmResponse.Error("Kimi response did not contain a message")
                val parts = mutableListOf<LlmResponsePart>()
                val content = message[KEY_CONTENT]
                if (content != null && content !is JsonNull) {
                    val text = content.jsonPrimitive.content
                    if (text.isNotEmpty()) {
                        parts.add(LlmResponsePart.Text(text))
                    }
                }
                message[KEY_TOOL_CALLS]?.jsonArray?.forEach { toolCallElement ->
                    val toolCall = toolCallElement.jsonObject
                    val callId =
                        toolCall[KEY_ID]?.jsonPrimitive?.content
                            ?: return LlmResponse.Error("Kimi tool call did not contain an ID")
                    val function =
                        toolCall[KEY_FUNCTION]?.jsonObject
                            ?: return LlmResponse.Error("Kimi tool call did not contain a function")
                    val name =
                        function[KEY_NAME]?.jsonPrimitive?.content
                            ?: return LlmResponse.Error("Kimi tool call did not contain a function name")
                    val matchingTool =
                        tools.find { toolConverter.getToolName(it) == name }
                            ?: return LlmResponse.Error("Kimi called unknown function: $name")
                    val argumentsText =
                        function[KEY_ARGUMENTS]?.jsonPrimitive?.content
                            ?: return LlmResponse.Error("Kimi tool call did not contain arguments")
                    val arguments = Json.parseToJsonElement(argumentsText).jsonObject
                    parts.add(
                        LlmResponsePart.ToolCall(
                            packageName = matchingTool.packageName,
                            functionId = matchingTool.id,
                            arguments = arguments.mapValues { it.value.toPrimitive() },
                            callId = callId,
                        ),
                    )
                }
                if (parts.isEmpty()) {
                    return LlmResponse.Error(
                        "Kimi response did not contain text or tool calls" +
                            (finishReason?.let { " (finish_reason=$it)" } ?: ""),
                    )
                }
                val continuation = STATE_PREFIX + JsonArray(messages + message).toString()
                LlmResponse.Success(interactionId = continuation, parts = parts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Kimi response", e)
                LlmResponse.Error("Invalid Kimi response: ${e.message}")
            }
        }

        private fun decodePreviousMessages(previousInteractionId: String?): JsonArray {
            if (previousInteractionId == null || !previousInteractionId.startsWith(STATE_PREFIX)) {
                return buildJsonArray {
                    add(
                        buildJsonObject {
                            put(KEY_ROLE, ROLE_SYSTEM)
                            put(KEY_CONTENT, getSystemInstruction())
                        },
                    )
                }
            }
            return Json.parseToJsonElement(previousInteractionId.removePrefix(STATE_PREFIX)).jsonArray
        }

        private fun LlmInput.toMessages(): JsonArray =
            buildJsonArray {
                when (this@toMessages) {
                    is LlmInput.UserMessage ->
                        add(
                            buildJsonObject {
                                put(KEY_ROLE, ROLE_USER)
                                put(KEY_CONTENT, text)
                            },
                        )
                    is LlmInput.ToolResponse ->
                        outputs.forEach { output ->
                            add(
                                buildJsonObject {
                                    put(KEY_ROLE, ROLE_TOOL)
                                    put(KEY_TOOL_CALL_ID, output.callId)
                                    put(KEY_CONTENT, output.result)
                                },
                            )
                        }
                }
            }

        private fun convertTools(tools: List<AppFunctionMetadata>): JsonArray {
            val namedTools =
                tools
                    .sortedByDescending { it.id.startsWith(it.packageName) }
                    .map { it to toolConverter.getToolName(it) }
            val duplicateName = namedTools.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }
            require(duplicateName == null) {
                "Multiple AppFunctions map to the Kimi tool name '${duplicateName?.key}'"
            }
            return buildJsonArray {
                namedTools.forEach { (tool, _) ->
                    val functionSchema = normalizeOpenAiSchema(toolConverter.convert(tool))
                    add(
                        buildJsonObject {
                            put(KEY_TYPE, VALUE_FUNCTION)
                            put(KEY_FUNCTION, functionSchema)
                        },
                    )
                }
            }
        }

        private fun normalizeOpenAiSchema(
            element: JsonElement,
            containsPropertyNames: Boolean = false,
        ): JsonElement =
            when (element) {
                is JsonObject ->
                    JsonObject(
                        element.entries
                            .filterNot { (key, value) ->
                                key == KEY_FORMAT &&
                                    value is JsonPrimitive &&
                                    value.contentOrNull == VALUE_FILE_REFERENCE
                            }
                            .associate { (key, value) ->
                                val normalizedKey =
                                    if (!containsPropertyNames && key == KEY_ENUMS) KEY_ENUM else key
                                normalizedKey to
                                    normalizeOpenAiSchema(
                                        value,
                                        containsPropertyNames =
                                            !containsPropertyNames && key == KEY_PROPERTIES,
                                    )
                            },
                    )
                is JsonArray -> JsonArray(element.map { normalizeOpenAiSchema(it) })
                is JsonPrimitive -> element
            }

        private fun JsonElement.toPrimitive(): Any? =
            when (this) {
                is JsonPrimitive -> {
                    if (isString) return content
                    content.toBooleanStrictOrNull()
                        ?: content.toLongOrNull()
                        ?: content.toDoubleOrNull()
                }
                is JsonObject -> mapValues { it.value.toPrimitive() }
                is JsonArray -> map { it.toPrimitive() }
            }

        private fun getSystemInstruction(): String {
            val currentDate = LocalDate.now().toString()
            return """
                You are an AI assistant running on Android. Today's date is $currentDate.
                Always reply to the user using concise, friendly, natural human language. Never respond to the user with raw JSON or structured code blocks unless explicitly asked to write code.
                When a user asks you to generate an image, call the generateImage tool. After generateImage completes, confirm to the user in a natural sentence (for example: "I have generated that image for you.").
                When a user asks you to generate an image and use it in an app (for example, setting a chat wallpaper or attaching an image to a note), first call generateImage, then call the target app function passing the returned imageUri.
                """.trimIndent()
        }

        companion object {
            private const val KIMI_CHAT_COMPLETIONS_URL =
                "https://api.kimi.com/coding/v1/chat/completions"
            private const val USER_AGENT = "AppFunctions-Agent/0.0.0"
            private const val STATE_PREFIX = "kimi:"
            private const val TAG = "KimiProvider"
            private const val KEY_MODEL = "model"
            private const val KEY_MESSAGES = "messages"
            private const val KEY_TOOLS = "tools"
            private const val KEY_CHOICES = "choices"
            private const val KEY_MESSAGE = "message"
            private const val KEY_FINISH_REASON = "finish_reason"
            private const val KEY_ROLE = "role"
            private const val KEY_CONTENT = "content"
            private const val KEY_TOOL_CALLS = "tool_calls"
            private const val KEY_TOOL_CALL_ID = "tool_call_id"
            private const val KEY_ID = "id"
            private const val KEY_TYPE = "type"
            private const val KEY_FUNCTION = "function"
            private const val KEY_NAME = "name"
            private const val KEY_ARGUMENTS = "arguments"
            private const val KEY_ENUMS = "enums"
            private const val KEY_ENUM = "enum"
            private const val KEY_FORMAT = "format"
            private const val KEY_PROPERTIES = "properties"
            private const val ROLE_SYSTEM = "system"
            private const val ROLE_USER = "user"
            private const val ROLE_TOOL = "tool"
            private const val VALUE_FUNCTION = "function"
            private const val VALUE_LENGTH = "length"
            private const val VALUE_FILE_REFERENCE = "file_reference"
        }
    }
