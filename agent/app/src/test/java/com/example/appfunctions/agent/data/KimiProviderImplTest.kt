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
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.example.appfunctions.agent.domain.LlmInput
import com.example.appfunctions.agent.domain.LlmResponse
import com.example.appfunctions.agent.domain.LlmResponsePart
import com.example.appfunctions.agent.domain.ToolOutput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiProviderImplTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @Test
    fun generateResponse_userMessage_usesOpenAiCompatibleEndpointAndReturnsText() =
        runBlocking {
            var requestUrl = ""
            var authorization = ""
            var userAgent = ""
            var requestBody = ""
            val mockEngine =
                MockEngine { request ->
                    requestUrl = request.url.toString()
                    authorization = request.headers["Authorization"].orEmpty()
                    userAgent = request.headers["User-Agent"].orEmpty()
                    requestBody = (request.body as TextContent).text
                    respond(
                        content =
                            """
                            {
                              "id": "chatcmpl_123",
                              "choices": [
                                {"message": {"role": "assistant", "content": "Hello from Kimi"}}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val httpClient =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val provider = KimiProviderImpl(httpClient, GeminiToolConverter())

            val response =
                provider.generateResponse(
                    previousInteractionId = null,
                    input = LlmInput.UserMessage("Hi"),
                    tools = emptyList(),
                    apiKey = "test_key",
                    modelName = "kimi-for-coding",
                )

            assertEquals("https://api.kimi.com/coding/v1/chat/completions", requestUrl)
            assertEquals("Bearer test_key", authorization)
            assertEquals("AppFunctions-Agent/0.0.0", userAgent)
            val body = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals("kimi-for-coding", body["model"]?.jsonPrimitive?.content)
            val messages = body.getValue("messages").jsonArray
            assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("Hi", messages[1].jsonObject["content"]?.jsonPrimitive?.content)

            assertTrue(response is LlmResponse.Success)
            response as LlmResponse.Success
            assertTrue(response.interactionId.startsWith("kimi:"))
            assertEquals("Hello from Kimi", (response.parts.single() as LlmResponsePart.Text).text)
        }

    @Test
    fun generateResponse_toolResult_preservesAssistantToolCallAndReturnsFinalText() =
        runBlocking {
            val requestBodies = mutableListOf<String>()
            var requestCount = 0
            val mockEngine =
                MockEngine { request ->
                    requestBodies.add((request.body as TextContent).text)
                    val responseBody =
                        if (requestCount++ == 0) {
                            """
                            {
                              "id": "chatcmpl_tool",
                              "choices": [{
                                "message": {
                                  "role": "assistant",
                                  "content": null,
                                  "tool_calls": [{
                                    "id": "call_123",
                                    "type": "function",
                                    "function": {
                                      "name": "my_function",
                                      "arguments": "{\"value\":\"hello\"}"
                                    }
                                  }]
                                }
                              }]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "id": "chatcmpl_final",
                              "choices": [{
                                "message": {"role": "assistant", "content": "Done"}
                              }]
                            }
                            """.trimIndent()
                        }
                    respond(
                        content = responseBody,
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val httpClient =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val provider = KimiProviderImpl(httpClient, GeminiToolConverter())
            val tool = createTool()

            val firstResponse =
                provider.generateResponse(
                    previousInteractionId = null,
                    input = LlmInput.UserMessage("Run it"),
                    tools = listOf(tool),
                    apiKey = "test_key",
                    modelName = "kimi-for-coding",
                ) as LlmResponse.Success

            val toolCall = firstResponse.parts.single() as LlmResponsePart.ToolCall
            assertEquals("call_123", toolCall.callId)
            assertEquals("hello", toolCall.arguments["value"])

            val secondResponse =
                provider.generateResponse(
                    previousInteractionId = firstResponse.interactionId,
                    input =
                        LlmInput.ToolResponse(
                            listOf(
                                ToolOutput(
                                    functionId = tool.id,
                                    callId = toolCall.callId,
                                    result = "{\"success\":true}",
                                ),
                            ),
                        ),
                    tools = listOf(tool),
                    apiKey = "test_key",
                    modelName = "kimi-for-coding",
                ) as LlmResponse.Success

            assertEquals("Done", (secondResponse.parts.single() as LlmResponsePart.Text).text)
            val firstBody = Json.parseToJsonElement(requestBodies.first()).jsonObject
            assertFalse(firstBody.containsKey("service_tier"))
            val function =
                firstBody.getValue("tools").jsonArray[0].jsonObject
                    .getValue("function")
                    .jsonObject
            assertEquals("my_function", function["name"]?.jsonPrimitive?.content)
            val valueSchema =
                function.getValue("parameters").jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("value")
                    .jsonObject
            assertTrue(valueSchema.containsKey("enum"))
            assertFalse(valueSchema.containsKey("enums"))
            val properties =
                function.getValue("parameters").jsonObject
                    .getValue("properties")
                    .jsonObject
            assertTrue(properties.containsKey("enums"))
            assertTrue(properties.getValue("enums").jsonObject.containsKey("enum"))
            assertTrue(properties.containsKey("properties"))
            assertTrue(properties.getValue("properties").jsonObject.containsKey("enum"))

            val secondBody = Json.parseToJsonElement(requestBodies[1]).jsonObject
            val messages = secondBody.getValue("messages").jsonArray
            assertEquals(
                listOf("system", "user", "assistant", "tool"),
                messages.map {
                    it.jsonObject.getValue("role").jsonPrimitive.content
                },
            )
            assertEquals(
                "call_123",
                messages.last().jsonObject["tool_call_id"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun generateResponse_cancelledRequest_rethrowsCancellation() =
        runBlocking {
            val provider =
                KimiProviderImpl(
                    HttpClient(MockEngine { throw CancellationException("cancelled") }) {
                        install(ContentNegotiation) { json() }
                    },
                    GeminiToolConverter(),
                )

            var cancellationThrown = false
            try {
                provider.generateResponse(
                    previousInteractionId = null,
                    input = LlmInput.UserMessage("Hi"),
                    tools = emptyList(),
                    apiKey = "test_key",
                    modelName = "kimi-for-coding",
                )
            } catch (_: CancellationException) {
                cancellationThrown = true
            }

            assertTrue(cancellationThrown)
        }

    @Test
    fun generateResponse_emptyAssistantMessage_returnsError() =
        runBlocking {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "choices": [{
                                "finish_reason": "content_filter",
                                "message": {"role": "assistant", "content": null}
                              }]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val provider =
                KimiProviderImpl(
                    HttpClient(mockEngine) { install(ContentNegotiation) { json() } },
                    GeminiToolConverter(),
                )

            val response =
                provider.generateResponse(
                    previousInteractionId = null,
                    input = LlmInput.UserMessage("Hi"),
                    tools = emptyList(),
                    apiKey = "test_key",
                    modelName = "kimi-for-coding",
                )

            assertTrue(response is LlmResponse.Error)
        }

    private fun createTool(): AppFunctionMetadata =
        AppFunctionMetadata(
            id = "my_function",
            packageName = "com.example",
            isEnabled = true,
            schema = null,
            parameters =
                listOf(
                    AppFunctionParameterMetadata(
                        name = "value",
                        isRequired = true,
                        dataType =
                            AppFunctionStringTypeMetadata(
                                isNullable = false,
                                enumValues = setOf("hello", "world"),
                            ),
                        description = "Value to pass",
                    ),
                    AppFunctionParameterMetadata(
                        name = "enums",
                        isRequired = true,
                        dataType =
                            AppFunctionStringTypeMetadata(
                                isNullable = false,
                                enumValues = setOf("one", "two"),
                            ),
                        description = "A parameter whose name matches the Gemini schema keyword",
                    ),
                    AppFunctionParameterMetadata(
                        name = "properties",
                        isRequired = true,
                        dataType =
                            AppFunctionStringTypeMetadata(
                                isNullable = false,
                                enumValues = setOf("first", "second"),
                            ),
                        description = "A parameter whose name matches a JSON Schema keyword",
                    ),
                ),
            response =
                AppFunctionResponseMetadata(
                    valueType = AppFunctionStringTypeMetadata(isNullable = false),
                    description = "",
                ),
            components = AppFunctionComponentsMetadata(emptyMap()),
            description = "Test function",
            deprecation = null,
        )
}
