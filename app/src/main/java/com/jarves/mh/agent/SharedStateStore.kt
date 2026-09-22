package com.jarves.mh.agent

import java.util.concurrent.ConcurrentHashMap

class SharedStateStore {
    private val taskStates = ConcurrentHashMap<String, TaskState>()
    private val sharedContext = ConcurrentHashMap<String, String>()
    private val workerStates = ConcurrentHashMap<AgentId, AgentState>()

    data class TaskState(
        val taskId: String,
        val subtasks: MutableMap<String, SubtaskState> = mutableMapOf(),
        val output: String = "",
        val status: TaskStatus = TaskStatus.Idle
    )

    data class SubtaskState(
        val subtaskId: String,
        val assignedAgentId: AgentId,
        val status: TaskStatus = TaskStatus.Idle,
        val output: String = ""
    )

    fun setTaskState(taskId: String, state: TaskState) {
        taskStates[taskId] = state
    }

    fun getTaskState(taskId: String): TaskState? {
        return taskStates[taskId]
    }

    fun setAgentState(agentId: AgentId, state: AgentState) {
        workerStates[agentId] = state
    }

    fun getAgentState(agentId: AgentId): AgentState? {
        return workerStates[agentId]
    }

    fun getAllAgentStates(): Map<AgentId, AgentState> {
        return workerStates.toMap()
    }

    fun setContext(key: String, value: String) {
        sharedContext[key] = value
    }

    fun getContext(key: String): String? {
        return sharedContext[key]
    }

    fun getContextAll(): Map<String, String> {
        return sharedContext.toMap()
    }
}
