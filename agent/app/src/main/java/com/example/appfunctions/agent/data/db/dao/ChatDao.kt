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
package com.example.appfunctions.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.appfunctions.agent.data.LlmModel
import com.example.appfunctions.agent.data.db.entities.MessageEntity
import com.example.appfunctions.agent.data.db.entities.MessageProcessingStatus
import com.example.appfunctions.agent.data.db.entities.ThreadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesForThread(threadId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET processingStatus = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(
        messageId: String,
        status: MessageProcessingStatus,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: ThreadEntity)

    @Query(
        "UPDATE threads SET latestInteractionId = :interactionId " +
            "WHERE threadId = :threadId AND llmModel = :expectedModel",
    )
    suspend fun updateLatestInteractionId(
        threadId: String,
        interactionId: String,
        expectedModel: LlmModel,
    ): Int

    @Query(
        "UPDATE threads SET llmModel = :llmModel, " +
            "latestInteractionId = CASE WHEN llmModel = :llmModel THEN latestInteractionId ELSE NULL END " +
            "WHERE threadId = :threadId",
    )
    suspend fun updateThreadModel(
        threadId: String,
        llmModel: LlmModel,
    )

    @Query("SELECT latestInteractionId FROM threads WHERE threadId = :threadId")
    fun getLatestInteractionId(threadId: String): Flow<String?>

    @Query("SELECT * FROM threads ORDER BY createdAt DESC")
    fun getThreads(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE threadId = :threadId")
    fun getThread(threadId: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE latestInteractionId = :interactionId")
    fun getThreadByInteractionId(interactionId: String): Flow<ThreadEntity?>

    @Query("DELETE FROM threads WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: String)
}
