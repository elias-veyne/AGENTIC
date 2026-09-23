package com.jarves.mh.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class HeartbeatMonitor(private val intervalMs: Long, private val failuresThreshold: Int) {
    private val _events = MutableSharedFlow<HeartbeatEvent>(extraBufferCapacity = 50)
    val events: Flow<HeartbeatEvent> = _events.asSharedFlow()

    suspend fun heartbeat(agentId: AgentId) {
        _events.emit(HeartbeatEvent.Received(agentId))
    }

    suspend fun checkHealth(agentId: AgentId, lastSeen: Long) {
        val timeSinceHeartbeat = System.currentTimeMillis() - lastSeen
        val failures = (timeSinceHeartbeat / intervalMs).toInt()

        if (failures >= failuresThreshold) {
            _events.emit(HeartbeatEvent.Failed(agentId, failures))
        }
    }
}

sealed class HeartbeatEvent {
    data class Received(val agentId: AgentId) : HeartbeatEvent()
    data class Failed(val agentId: AgentId, val missedBeats: Int) : HeartbeatEvent()
}
