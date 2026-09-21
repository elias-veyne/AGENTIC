package com.jarves.mh.model

/**
 * Tracks the state of an individual agent worker.
 */
data class WorkerState(
    val id: String,
    val agentManager: AgentManager,
    val status: WorkerStatus = WorkerStatus.Idle,
    val currentSubtaskId: String? = null,
    val lastHeartbeat: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0
)

/**
 * Possible states for a worker.
 */
enum class WorkerStatus {
    Idle,
    Busy,
    Unhealthy,
    Offline
}

/**
 * Event emitted by the worker monitor.
 */
sealed class WorkerEvent {
    data class WorkerAvailable(val workerId: String) : WorkerEvent()
    data class WorkerUnavailable(val workerId: String, val reason: String) : WorkerEvent()
    data class TaskAssigned(val workerId: String, val taskId: String) : WorkerEvent()
    data class TaskCompleted(val workerId: String, val taskId: String) : WorkerEvent()
    object HeartbeatReceived : WorkerEvent()
}

/**
 * Manager for worker lifecycle and health monitoring.
 */
class WorkerMonitor(
    private val config: SystemConfig
) {
    private val workerStates = mutableMapOf<String, WorkerState>()
    private val eventListeners = mutableListOf<(WorkerEvent) -> Unit>()

    fun addListener(listener: (WorkerEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun registerWorker(id: String, manager: AgentManager) {
        workerStates[id] = WorkerState(id = id, agentManager = manager)
        emit(WorkerEvent.WorkerAvailable(id))
    }

    fun unregisterWorker(id: String) {
        workerStates.remove(id)
        emit(WorkerEvent.WorkerUnavailable(id, "Removed"))
    }

    fun checkHealth() {
        val now = System.currentTimeMillis()
        workerStates.values.forEach { state ->
            val timeSinceHeartbeat = (now - state.lastHeartbeat) / 1000
            
            when {
                timeSinceHeartbeat > config.heartbeatIntervalSeconds * 3 -> {
                    // Critical failure
                    if (state.status == WorkerStatus.Unhealthy) {
                        emit(WorkerEvent.WorkerUnavailable(state.id, "Missed heartbeats"))
                    }
                    workerStates[state.id] = state.copy(status = WorkerStatus.Unhealthy, consecutiveFailures = state.consecutiveFailures + 1)
                }
                timeSinceHeartbeat > config.heartbeatIntervalSeconds -> {
                    // Warning
                    workerStates[state.id] = state.copy(consecutiveFailures = state.consecutiveFailures + 1)
                }
                else -> {
                    workerStates[state.id] = state.copy(consecutiveFailures = 0)
                }
            }
        }
    }

    fun recordHeartbeat(workerId: String) {
        val state = workerStates[workerId] ?: return
        workerStates[workerId] = state.copy(lastHeartbeat = System.currentTimeMillis())
        emit(WorkerEvent.HeartbeatReceived)
    }

    fun recordTaskCompleted(workerId: String, taskId: String) {
        val state = workerStates[workerId] ?: return
        workerStates[workerId] = state.copy(status = WorkerStatus.Idle, currentSubtaskId = null)
        emit(WorkerEvent.TaskCompleted(workerId, taskId))
    }

    fun getAvailableWorkers(): List<String> {
        return workerStates.values.filter { it.status == WorkerStatus.Idle }.map { it.id }
    }

    private fun emit(event: WorkerEvent) {
        eventListeners.forEach { it(event) }
    }
}
