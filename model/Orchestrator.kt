package com.jarves.mh.model

import kotlin.random.Random

/**
 * The Orchestrator manages task distribution, worker assignment, and execution flow.
 * This is the core of the Agentic mode.
 */
class Orchestrator(
    private val config: SystemConfig,
    private val workerMonitor: WorkerMonitor
) {
    private val subtasks = mutableMapOf<String, Subtask>()
    private val completedSubtasks = mutableMapOf<String, Subtask>()
    private val taskResults = mutableMapOf<String, TaskResult>()
    
    // Task completion callbacks
    private val taskListeners = mutableListOf<(String, TaskResult) -> Unit>()

    fun addTaskListener(listener: (String, TaskResult) -> Unit) {
        taskListeners.add(listener)
    }

    /**
     * Decompose a high-level task into subtasks.
     * (In production, this would use an LLM to analyze and split)
     */
    fun decomposeTask(task: Task): List<Subtask> {
        val subtaskId = "sub_${task.id}_${Random.nextInt(10000)}"
        return listOf(
            Subtask(
                id = subtaskId,
                parentTaskId = task.id,
                instruction = task.prompt,
                status = SubtaskStatus.Pending
            )
        )
    }

    /**
     * Main entry point for submitting tasks to the orchestrator.
     */
    fun submitTask(task: Task): String {
        val subtasksToRun = decomposeTask(task)
        
        subtasksToRun.forEach { subtask ->
            subtasks[subtask.id] = subtask
            // Try to assign immediately
            assignSubtask(subtask)
        }
        
        return task.id
    }

    /**
     * Attempt to assign a pending subtask to an available worker.
     */
    private fun assignSubtask(subtask: Subtask) {
        val availableWorkers = workerMonitor.getAvailableWorkers()
        
        when {
            availableWorkers.isEmpty() -> {
                // No workers available, subtask stays pending
                subtasks[subtask.id] = subtask.copy(status = SubtaskStatus.Blocked)
            }
            else -> {
                // Simple round-robin assignment
                val workerId = availableWorkers.random()
                
                // Update subtask
                val updatedSubtask = subtask.copy(
                    assignedWorkerId = workerId,
                    status = SubtaskStatus.Assigned
                )
                subtasks[subtask.id] = updatedSubtask
                
                // Notify worker monitor
                workerMonitor.recordTaskCompleted(workerId, subtask.id) // simplified for demo
                
                // In production: send actual execution request to worker
                executeSubtaskOnWorker(updatedSubtask)
            }
        }
    }

    /**
     * Execute a subtask on a specific worker.
     */
    private fun executeSubtaskOnWorker(subtask: Subtask) {
        val workerState = subtask.assignedWorkerId?.let { workerMonitor.workerStates[it] }
        
        if (workerState != null) {
            try {
                val taskResult = workerState.agentManager.submitTask(subtask.parentTaskId, subtask.instruction, subtask.dependencies.map { "depends_on: $it" }.associate { it to "true" })
                
                // Update subtask
                val completedSubtask = subtask.copy(
                    status = SubtaskStatus.Completed,
                    result = taskResult
                )
                subtasks[subtask.id] = completedSubtask
                
                // Notify completion
                emitTaskComplete(subtask.parentTaskId)
                
            } catch (e: Exception) {
                val failedSubtask = subtask.copy(
                    status = SubtaskStatus.Failed,
                    result = e.message
                )
                subtasks[subtask.id] = failedSubtask
                
                // Update worker failure count
                workerState.consecutiveFailures++
            }
        }
    }

    private fun emitTaskComplete(taskId: String) {
        val subtasks = subtasks.values.filter { it.parentTaskId == taskId }
        
        // Check if all subtasks are complete
        val allComplete = subtasks.all { it.status == SubtaskStatus.Completed }
        
        if (allComplete) {
            val result = subtasks.first().result ?: "Task completed successfully"
            val taskResult = TaskResult(taskId = taskId, output = result)
            
            taskResults[taskId] = taskResult
            
            taskListeners.forEach { it(taskId, taskResult) }
        }
    }

    fun getTaskStatus(taskId: String): TaskStatus {
        val subtasks = subtasks.values.filter { it.parentTaskId == taskId }
        
        return when {
            subtasks.isEmpty() -> TaskStatus.Idle
            subtasks.all { it.status == SubtaskStatus.Completed } -> {
                val result = subtasks.first().result ?: ""
                TaskStatus.Complete(result)
            }
            subtasks.any { it.status == SubtaskStatus.Failed } -> {
                val error = subtasks.first { it.status == SubtaskStatus.Failed }.result ?: "Unknown error"
                TaskStatus.Failed(error)
            }
            else -> TaskStatus.Processing
        }
    }
}
