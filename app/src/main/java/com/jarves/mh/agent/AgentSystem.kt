package com.jarves.mh.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AgentSystem {
    private val bus = MessageBus()
    private val store = SharedStateStore()
    private val config = SystemConfig()
    private val heartbeat = HeartbeatMonitor(config.heartbeatIntervalMs, config.heartbeatFailuresThreshold)
    private val runtime = AgentRuntime(bus, store)
    private val orchestrator = Orchestrator(bus, store, config)
    private val cooperative = CooperativeSession(bus, store)
    private val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 50)
    val events: Flow<AgentEvent> = events.asSharedFlow()

    fun getOrchestrator(): Orchestrator = orchestrator
    fun getCooperative(): CooperativeSession = cooperative
    fun getStore(): SharedStateStore = store
    fun getRuntime(): AgentRuntime = runtime

    suspend fun startAgent(agentId: AgentId) {
        store.setAgentState(agentId, AgentState(agentId))
        events.emit(AgentEvent.AgentStarted(agentId))
    }

    suspend fun stopAgent(agentId: AgentId) {
        events.emit(AgentEvent.AgentStopped(agentId))
    }

    suspend fun recordHeartbeat(agentId: AgentId) {
        heartbeat.heartbeat(agentId)
    }

    suspend fun submitTask(taskId: String, instruction: String, onOutput: suspend (String) -> Unit) {
        events.emit(AgentEvent.TaskSubmitted(taskId))
        val output = runtime.execute(taskId, instruction) { }
        events.emit(AgentEvent.TaskCompleted(taskId, output))
        onOutput(output)
    }

    suspend fun continueTask(taskId: String, checkpoint: String) {
        events.emit(AgentEvent.TaskResumed(taskId, checkpoint))
    }

    suspend fun reportMismatch(taskId: String, contract: String) {
        bus.send(AgentMessage.MismatchAlert(taskId, contract))
    }

    fun getConfig(): SystemConfig = config
}

sealed class AgentEvent {
    data class AgentStarted(val agentId: AgentId) : AgentEvent()
    data class AgentStopped(val agentId: AgentId) : AgentEvent()
    data class TaskSubmitted(val taskId: String) : AgentEvent()
    data class TaskCompleted(val taskId: String, val output: String) : AgentEvent()
    data class TaskResumed(val taskId: String, val checkpoint: String) : AgentEvent()
    data class AgentFailed(val agentId: AgentId, val reason: String) : AgentEvent()
}
