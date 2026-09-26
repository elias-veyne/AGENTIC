package com.jarves.mh.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class HeartbeatMonitor(
    private val intervalMs: Long, 
    private val failuresThreshold: Int,
    private val retryCount: Int = 3
) {
    private val _events = MutableSharedFlow<HeartbeatEvent>(extraBufferCapacity = 50)
    val events: Flow<HeartbeatEvent> = _events.asSharedFlow()
    
    private val failedAgents = mutableMapOf<AgentId, Int>()

    suspend fun heartbeat(agentId: AgentId) {
        failedAgents.remove(agentId)
        _events.emit(HeartbeatEvent.Received(agentId))
    }

    suspend fun checkHealth(agentId: AgentId, lastSeen: Long) {
        val timeSinceHeartbeat = System.currentTimeMillis() - lastSeen
        val failures = (timeSinceHeartbeat / intervalMs).toInt()

        if (failures >= failuresThreshold) {
            val currentRetries = failedAgents.getOrDefault(agentId, 0)
            if (currentRetries >= retryCount) {
                _events.emit(HeartbeatEvent.MaxRetriesExceeded(agentId, currentRetries))
            } else {
                failedAgents[agentId] = currentRetries + 1
                _events.emit(HeartbeatEvent.Failed(agentId, failures))
            }
        }
    }
    
    fun retryCount(agentId: AgentId): Int = failedAgents.getOrDefault(agentId, 0)
}

sealed class HeartbeatEvent {
    data class Received(val agentId: AgentId) : HeartbeatEvent()
    data class Failed(val agentId: AgentId, val missedBeats: Int) : HeartbeatEvent()
    data class MaxRetriesExceeded(val agentId: AgentId, val totalRetries: Int) : HeartbeatEvent()
}
