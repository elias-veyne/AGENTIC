package com.jarves.mh.agent

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

data class AgentId(val value: String) {
    companion object {
        fun random() = AgentId(UUID.randomUUID().toString())
        fun orchestrator() = AgentId("orchestrator")
        fun worker(name: String) = AgentId("worker_$name")
        fun peer(name: String) = AgentId("peer_$name")
    }
}

sealed class AgentMessage {
    data class TaskAssignment(val taskId: String, val instruction: String, val context: Map<String, String>) : AgentMessage()
    data class StatusUpdate(val taskId: String, val status: TaskStatus) : AgentMessage()
    data class MismatchAlert(val taskId: String, val contractContract: String) : AgentMessage()
    data class ContinueTask(val taskId: String, val checkpoint: String, val retryCount: Int) : AgentMessage()
    object Heartbeat : AgentMessage()
    data class TaskComplete(val taskId: String, val output: String) : AgentMessage()
    data class TaskFailed(val taskId: String, val error: String) : AgentMessage()
}

sealed class TaskStatus {
    object Idle : TaskStatus()
    object Assigned : TaskStatus()
    object Working : TaskStatus()
    object Blocked : TaskStatus()
    object Failed : TaskStatus()
    object Resuming : TaskStatus()
    object Done : TaskStatus()
}

data class AgentState(
    val agentId: AgentId,
    val taskId: String? = null,
    val status: TaskStatus = TaskStatus.Idle,
    val lastHeartbeat: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0
)

data class AuthToken(val token: String)
