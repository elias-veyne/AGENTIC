package com.jarves.mh.model

import com.jarves.mh.runtime.DeepSeekHarnessBridge

/**
 * Abstraction layer for agent execution.
 * Currently wraps the single DSH instance, but designed to support
 * multiple agents in the future (Orchestrator + Workers pattern).
 */
interface AgentManager {
    /**
     * Send a task to the agent.
     * @return A task session ID for tracking status and results
     */
    fun submitTask(taskId: String, prompt: String, context: Map<String, String>): String
    
    /**
     * Get the current status of a task.
     */
    fun getTaskStatus(taskId: String): TaskStatus
    
    /**
     * Request cancellation of a running task.
     */
    fun cancelTask(taskId: String): Boolean
    
    /**
     * Internal health check (heartbeat).
     * Returns true if the agent is responsive.
     */
    fun isHealthy(): Boolean
    
    /**
     * Get agent metadata (name, version, capabilities)
     */
    fun getAgentInfo(): AgentInfo
}

data class AgentInfo(
    val name: String,
    val version: String,
    val capabilities: List<String>
)

sealed class TaskStatus {
    object Idle : TaskStatus()
    object Processing : TaskStatus()
    data class Complete(val result: String) : TaskStatus()
    data class Failed(val error: String) : TaskStatus()
}

/**
 * Default implementation wrapping DeepSeek Harness.
 * In Multi-Agent mode, this would delegate to an Orchestrator.
 */
class DeepSeekAgentManager : AgentManager {
    private val bridge = DeepSeekHarnessBridge()
    
    override fun submitTask(taskId: String, prompt: String, context: Map<String, String>): String {
        // For now, the task ID is the request ID
        bridge.execute(prompt, context)
        return taskId
    }
    
    override fun getTaskStatus(taskId: String): TaskStatus {
        // DSH streams results, so we rely on callback-driven updates
        // In a real implementation, this would check an internal task registry
        return TaskStatus.Idle
    }
    
    override fun cancelTask(taskId: String): Boolean {
        bridge.cancelCurrentExecution()
        return true
    }
    
    override fun isHealthy(): Boolean {
        try {
            bridge.healthCheck()
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    override fun getAgentInfo(): AgentInfo {
        return AgentInfo(
            name = "DeepSeek Harness",
            version = "0.1.0",
            capabilities = listOf("chat", "file-edit", "bash-exec", "web-preview")
        )
    }
}
