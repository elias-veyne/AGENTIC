package com.jarves.mh.agent

import com.jarves.mh.agent.SharedStateStore.TaskState
import kotlinx.coroutines.flow.Flow

/**
 * Executes a single (sub)task instruction and returns its textual output.
 *
 * The real implementation lands on the runtime bridge (a live PRoot session via
 * `RuntimeBridge.startSession`). Tests inject a fake so the engine core is
 * exercised without an Android runtime.
 */
fun interface TaskExecutor {
    suspend fun execute(instruction: String): String
}

class AgentRuntime(
    private val bus: MessageBus,
    private val store: SharedStateStore,
    private val executor: TaskExecutor,
) {
    suspend fun execute(
        taskId: String,
        instruction: String,
        onEvent: suspend (AgentMessage) -> Unit = {},
    ): String {
        store.setTaskState(taskId, TaskState(taskId, status = TaskStatus.Assigned))
        bus.send(AgentMessage.StatusUpdate(taskId, TaskStatus.Working))

        try {
            val result = executor.execute(instruction)
            onEvent(AgentMessage.StatusUpdate(taskId, TaskStatus.Done))
            store.setTaskState(
                taskId,
                store.getTaskState(taskId)?.copy(status = TaskStatus.Done, output = result)
                    ?: TaskState(taskId, status = TaskStatus.Done, output = result),
            )
            return result
        } catch (e: Exception) {
            onEvent(AgentMessage.TaskFailed(taskId, e.message ?: "Error"))
            store.setTaskState(
                taskId,
                store.getTaskState(taskId)?.copy(status = TaskStatus.Failed, output = e.message ?: "Error")
                    ?: TaskState(taskId, status = TaskStatus.Failed, output = e.message ?: "Error"),
            )
            throw e
        }
    }
}
