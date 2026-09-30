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

import com.example.appfunctions.agent.data.db.entities.MessageEntity
import com.example.appfunctions.agent.data.db.entities.MessageProcessingStatus
import com.example.appfunctions.agent.data.db.entities.ThreadEntity
import kotlinx.coroutines.flow.Flow

/** Repository to manage chat history and threads. */
interface ChatRepository {
    /** Returns a flow of messages for a specific thread. */
    fun getMessagesForThread(threadId: String): Flow<List<MessageEntity>>

    /** Inserts a message into the repository. */
    suspend fun sendMessage(message: MessageEntity)

    /** Updates the processing status of a message. */
    suspend fun updateMessageStatus(
        messageId: String,
        status: MessageProcessingStatus,
    )

    /** Creates a new thread. */
    suspend fun createThread(thread: ThreadEntity)

    /** Updates the model of a thread. */
    suspend fun updateThreadModel(
        threadId: String,
        llmModel: LlmModel,
    )

    /** Returns a flow of all threads. */
    fun getThreads(): Flow<List<ThreadEntity>>

    /** Returns a flow of the thread associated with a specific ID. */
    fun getThread(threadId: String): Flow<ThreadEntity?>

    /** Deletes a thread and its associated messages. */
    suspend fun deleteThread(threadId: String)

    /** Returns a flow of the latest interaction ID for a specific thread. */
    fun getLatestInteractionId(threadId: String): Flow<String?>

    /** Updates the latest interaction ID for a specific thread. */
    suspend fun updateLatestInteractionId(
        threadId: String,
        interactionId: String,
        expectedModel: LlmModel,
    ): Boolean

    /** Returns a flow of the thread associated with a specific interaction ID. */
    fun getThreadByInteractionId(interactionId: String): Flow<ThreadEntity?>
}
