package com.jarves.mh.model

/**
 * Represents a unit of work submitted to the agent system.
 */
data class Task(
    val id: String,
    val prompt: String,
    val context: Map<String, String>,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Subtask for the orchestrator to distribute among workers.
 */
data class Subtask(
    val id: String,
    val parentTaskId: String,
    val instruction: String,
    val dependencies: List<String> = emptyList(),
    val assignedWorkerId: String? = null,
    val status: SubtaskStatus = SubtaskStatus.Pending,
    val result: String? = null
)

/**
 * Status of a subtask within the orchestrator.
 */
enum class SubtaskStatus {
    Pending,
    Assigned,
    InProgress,
    Completed,
    Failed,
    Blocked // Waiting on another subtask
}

/**
 * Result of executing a task.
 */
data class TaskResult(
    val taskId: String,
    val output: String,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Message type for orchestrator-worker communication (internal).
 */
sealed class AgentMessage {
    data class StartTask(val task: Subtask) : AgentMessage()
    data class TaskComplete(val subtaskId: String, val result: String) : AgentMessage()
    data class TaskFailed(val subtaskId: String, val error: String) : AgentMessage()
    object Heartbeat : AgentMessage()
}
