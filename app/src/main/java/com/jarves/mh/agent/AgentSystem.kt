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
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 50)
    val events: Flow<AgentEvent> = _events.asSharedFlow()

    fun getOrchestrator(): Orchestrator = orchestrator
    fun getCooperative(): CooperativeSession = cooperative
    fun getStore(): SharedStateStore = store
    fun getRuntime(): AgentRuntime = runtime
    fun getHeartbeat(): HeartbeatMonitor = heartbeat

    suspend fun startAgent(agentId: AgentId) {
        store.setAgentState(agentId, AgentState(agentId))
        _events.emit(AgentEvent.AgentStarted(agentId))
    }

    suspend fun stopAgent(agentId: AgentId) {
        _events.emit(AgentEvent.AgentStopped(agentId))
    }

    suspend fun recordHeartbeat(agentId: AgentId) {
        heartbeat.heartbeat(agentId)
    }

    /**
     * Call this from a background coroutine to periodically check agent health.
     */
    suspend fun checkAgentHealth(agentId: AgentId) {
        val agentState = store.getAgentState(agentId) ?: return
        heartbeat.checkHealth(agentId, agentState.lastHeartbeat)
    }

    suspend fun submitTask(taskId: String, instruction: String, onOutput: suspend (String) -> Unit) {
        _events.emit(AgentEvent.TaskSubmitted(taskId))
        val output = runtime.execute(taskId, instruction) { }
        _events.emit(AgentEvent.TaskCompleted(taskId, output))
        onOutput(output)
    }

    /**
     * Resume a task after recovery from failure.
     */
    suspend fun continueTask(taskId: String, checkpoint: String) {
        _events.emit(AgentEvent.TaskResumed(taskId, checkpoint))
    }

    suspend fun reportMismatch(taskId: String, contract: String) {
        bus.send(AgentMessage.MismatchAlert(taskId, contract))
    }

    /**
     * Attempt to recover a failed agent by generating a recovery message.
     */
    suspend fun attemptRecovery(taskId: String, failedAgentId: AgentId): Boolean {
        val recoveryMsg = orchestrator.generateRecoveryMessage(taskId, failedAgentId)
        return if (recoveryMsg != null) {
            bus.send(recoveryMsg)
            true
        } else {
            false
        }
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
    data class RecoveryAttempt(val taskId: String, val agentId: AgentId, val success: Boolean) : AgentEvent()
}
