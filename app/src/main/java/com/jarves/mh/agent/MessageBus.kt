package com.jarves.mh.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MessageBus {
    private val _messages = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 100)
    val messages: Flow<AgentMessage> = _messages.asSharedFlow()

    suspend fun send(message: AgentMessage) = _messages.emit(message)
}
