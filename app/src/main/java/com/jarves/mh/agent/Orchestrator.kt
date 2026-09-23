package com.jarves.mh.agent

import com.jarves.mh.agent.SharedStateStore.TaskState
import kotlinx.coroutines.flow.Flow

class Orchestrator(
    private val bus: MessageBus,
    private val store: SharedStateStore,
    private val config: SystemConfig
) {
    private val subtasks = mutableMapOf<String, Subtask>()
    private val workers = mutableSetOf<AgentId>()

    data class Subtask(
        val subtaskId: String,
        val parentId: String,
        val instruction: String,
        val assignedWorker: AgentId? = null,
        val status: SubtaskStatus = SubtaskStatus.Pending
    )

    enum class SubtaskStatus { Pending, Assigned, InProgress, Completed, Failed, Blocked }

    fun registerWorker(id: AgentId) {
        workers.add(id)
    }

    fun getWorkers(): List<AgentId> = workers.toList()

    fun decompose(taskId: String, instruction: String): List<Subtask> {
        val ui = Subtask("ui_$taskId", taskId, instruction, null, SubtaskStatus.Pending)
        val backend = Subtask("backend_$taskId", taskId, instruction, null, SubtaskStatus.Pending)
        return listOf(ui, backend)
    }

    fun assign(subtasks: List<Subtask>, workers: List<AgentId>): List<Subtask> {
        if (workers.isEmpty()) return subtasks
        return subtasks.mapIndexed { i, st ->
            st.copy(assignedWorker = workers[i % workers.size], status = SubtaskStatus.Assigned)
        }
    }

    suspend fun onWorkerStatus(taskId: String, status: TaskStatus) {
        store.setTaskState(taskId, store.getTaskState(taskId)?.copy(status = status) ?: TaskState(taskId, status = status))
    }

    suspend fun inspectMismatch(taskId: String, uiOutput: String, backendOutput: String) {
        if (uiOutput.isNotBlank() && backendOutput.isNotBlank()) {
            if (contractsMismatch(uiOutput, backendOutput)) {
                bus.send(AgentMessage.MismatchAlert(taskId, "Contract mismatch between UI and backend"))
            }
        }
    }

    private fun contractsMismatch(ui: String, backend: String): Boolean {
        return !ui.contains("data") || !backend.contains("endpoint") ||
               (ui.contains("POST") && backend.contains("GET")) ||
               (ui.contains("PUT") && backend.contains("POST"))
    }

    fun getCheckpoint(taskId: String): String {
        return store.getTaskState(taskId)?.output ?: ""
    }
}
