package com.jarves.mh.model

import com.jarves.mh.runtime.DeepSeekHarnessBridge

/**
 * Abstraction layer for agent execution.
 */
interface AgentManager {
    fun submitTask(taskId: String, prompt: String, context: Map<String, String>): String
    fun getTaskStatus(taskId: String): TaskStatus
    fun cancelTask(taskId: String): Boolean
    fun isHealthy(): Boolean
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
 * Mode selector for the agent system.
 */
enum class AgentMode {
    /**
     * Direct mode - one agent handles everything.
     */
    SIMPLE,
    
    /**
     * Orchestrator mode - tasks are decomposed and distributed among workers.
     */
    AGENTIC
}

/**
 * Central agent manager that can switch modes.
 */
class AgentManagerFactory(
    private val config: SystemConfig
) {
    private var mode: AgentMode = AgentMode.SIMPLE
    private var orchestrator: Orchestrator? = null
    private var workerMonitor: WorkerMonitor? = null
    private val baseManager = DeepSeekAgentManager()

    fun setMode(newMode: AgentMode) {
        mode = newMode
        
        when (newMode) {
            AgentMode.SIMPLE -> {
                orchestrator = null
                workerMonitor = null
            }
            AgentMode.AGENTIC -> {
                workerMonitor = WorkerMonitor(config)
                orchestrator = Orchestrator(config, workerMonitor!!)
                // Initialize with base manager as a worker
                workerMonitor?.registerWorker("dsh_worker", baseManager)
            }
        }
    }

    fun getManagerForMode(mode: AgentMode): AgentManager {
        return when (mode) {
            AgentMode.SIMPLE -> baseManager
            AgentMode.AGENTIC -> orchestrator ?: baseManager
        }
    }
}

/**
 * Default implementation wrapping DeepSeek Harness (Simple Mode).
 */
class DeepSeekAgentManager : AgentManager {
    private val bridge = DeepSeekHarnessBridge()
    private var activeTaskId: String? = null

    override fun submitTask(taskId: String, prompt: String, context: Map<String, String>): String {
        activeTaskId = taskId
        bridge.execute(prompt, context)
        return taskId
    }
    
    override fun getTaskStatus(taskId: String): TaskStatus {
        return TaskStatus.Idle
    }
    
    override fun cancelTask(taskId: String): Boolean {
        if (activeTaskId == taskId) {
            bridge.cancelCurrentExecution()
        }
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
