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

import com.example.appfunctions.agent.data.db.dao.ChatDao
import com.example.appfunctions.agent.data.db.entities.MessageEntity
import com.example.appfunctions.agent.data.db.entities.MessageProcessingStatus
import com.example.appfunctions.agent.data.db.entities.ThreadEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed implementation of [ChatRepository]. */
@Singleton
class RoomChatRepository
    @Inject
    constructor(
        private val chatDao: ChatDao,
    ) : ChatRepository {
        override fun getMessagesForThread(threadId: String): Flow<List<MessageEntity>> {
            return chatDao.getMessagesForThread(threadId)
        }

        override suspend fun sendMessage(message: MessageEntity) {
            chatDao.insertMessage(message)
        }

        override suspend fun updateMessageStatus(
            messageId: String,
            status: MessageProcessingStatus,
        ) {
            chatDao.updateMessageStatus(messageId, status)
        }

        override suspend fun createThread(thread: ThreadEntity) {
            chatDao.insertThread(thread)
        }

        override suspend fun updateThreadModel(
            threadId: String,
            llmModel: LlmModel,
        ) {
            chatDao.updateThreadModel(threadId, llmModel)
        }

        override fun getThreads(): Flow<List<ThreadEntity>> {
            return chatDao.getThreads()
        }

        override fun getThread(threadId: String): Flow<ThreadEntity?> {
            return chatDao.getThread(threadId)
        }

        override suspend fun deleteThread(threadId: String) {
            chatDao.deleteThread(threadId)
        }

        override fun getLatestInteractionId(threadId: String): Flow<String?> {
            return chatDao.getLatestInteractionId(threadId)
        }

        override suspend fun updateLatestInteractionId(
            threadId: String,
            interactionId: String,
            expectedModel: LlmModel,
        ): Boolean {
            return chatDao.updateLatestInteractionId(threadId, interactionId, expectedModel) > 0
        }

        override fun getThreadByInteractionId(interactionId: String): Flow<ThreadEntity?> {
            return chatDao.getThreadByInteractionId(interactionId)
        }
    }
