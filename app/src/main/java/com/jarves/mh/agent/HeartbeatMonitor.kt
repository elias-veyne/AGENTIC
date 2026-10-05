package com.jarves.mh.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartbeatMonitor(
    private val intervalMs: Long, 
    private val failuresThreshold: Int,
    private val retryCount: Int = 3
) {
    private val _events = MutableSharedFlow<HeartbeatEvent>(extraBufferCapacity = 50)
    val events: Flow<HeartbeatEvent> = _events.asSharedFlow()
    
    private val failedAgents = mutableMapOf<AgentId, Int>()

    /** Wall-clock of each agent's last beat, so silence is actually detectable. */
    private val lastSeenAt = mutableMapOf<AgentId, Long>()

    /**
     * Agents currently being watched. Membership starts at the first beat: an agent that
     * never reported is not "unresponsive", it simply was never started.
     */
    private val watched = mutableSetOf<AgentId>()

    suspend fun heartbeat(agentId: AgentId) {
        failedAgents.remove(agentId)
        lastSeenAt[agentId] = System.currentTimeMillis()
        watched.add(agentId)
        _events.emit(HeartbeatEvent.Received(agentId))
    }

    /**
     * Stops watching a finished agent. Without this a worker that finished its shard
     * cleanly would be reported as unresponsive once its grace window elapsed.
     */
    fun retire(agentId: AgentId) {
        watched.remove(agentId)
        failedAgents.remove(agentId)
        lastSeenAt.remove(agentId)
    }

    suspend fun checkHealth(agentId: AgentId, lastSeen: Long) {
        // A retired agent is not merely unwatched by the ticker — it must stop counting
        // as unhealthy even when a caller checks it directly. Without this guard retire()
        // only hid the agent from [monitor], and a finished worker could still be
        // reported as unresponsive.
        if (agentId !in watched) return
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

    /**
     * Ticks and re-checks every watched agent against its last beat.
     *
     * This is the piece that was missing: `checkHealth` existed but had no caller, so
     * [HeartbeatEvent.Failed] and [HeartbeatEvent.MaxRetriesExceeded] could never be
     * emitted and no agent was ever detected as unresponsive.
     *
     * Callers must cancel the returned job when the run ends. With the default 30s
     * interval and a threshold of 3, silence is declared after ~90s, so the tick is not
     * the dominant term in detection latency.
     */
    fun monitor(scope: CoroutineScope, tickIntervalMs: Long = intervalMs): Job = scope.launch {
        while (isActive) {
            delay(tickIntervalMs)
            for (agentId in watched.toList()) {
                val last = lastSeenAt[agentId] ?: continue
                checkHealth(agentId, last)
            }
        }
    }
    
    fun retryCount(agentId: AgentId): Int = failedAgents[agentId] ?: 0
}

sealed class HeartbeatEvent {
    data class Received(val agentId: AgentId) : HeartbeatEvent()
    data class Failed(val agentId: AgentId, val missedBeats: Int) : HeartbeatEvent()
    data class MaxRetriesExceeded(val agentId: AgentId, val totalRetries: Int) : HeartbeatEvent()
}
