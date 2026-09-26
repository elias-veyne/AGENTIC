package com.jarves.mh.agent

import com.jarves.mh.agent.SharedStateStore.TaskState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

class Orchestrator(
    private val bus: MessageBus,
    private val store: SharedStateStore,
    private val config: SystemConfig,
) {
    private val workers = mutableSetOf<AgentId>()

    data class Subtask(
        val subtaskId: String,
        val parentId: String,
        val instruction: String,
        val assignedWorker: AgentId? = null,
        val status: SubtaskStatus = SubtaskStatus.Pending,
    )

    enum class SubtaskStatus { Pending, Assigned, InProgress, Completed, Failed, Blocked }

    /** Outcome of running a decomposed set of shards concurrently. */
    data class ShardRun(
        val taskId: String,
        val shards: List<ShardResult>,
        val allSucceeded: Boolean,
    ) {
        val mergedOutput: String
            get() = shards.joinToString("\n\n") { shard ->
                "### ${shard.subtaskId}\n${shard.output}".trimEnd()
            }
    }

    data class ShardResult(
        val subtaskId: String,
        val instruction: String,
        val worker: AgentId?,
        val output: String,
        val succeeded: Boolean,
        val error: String? = null,
    )

    fun registerWorker(id: AgentId) {
        workers.add(id)
    }

    fun getWorkers(): List<AgentId> = workers.toList()

    /** V3: LLM-driven decomposition over N workers. */
    suspend fun decompose(
        taskId: String,
        instruction: String,
        decomposer: Decomposer = Decomposer.DecomposedChild.Default,
    ): List<Subtask> {
        val children = decomposer.decompose(instruction)
        return children.map { child ->
            Subtask(
                subtaskId = child.id.ifBlank { "sub_$taskId" },
                parentId = taskId,
                instruction = child.instruction,
                status = SubtaskStatus.Pending,
            )
        }
    }

    fun assign(subtasks: List<Subtask>, workers: List<AgentId>): List<Subtask> {
        if (workers.isEmpty()) return subtasks
        return subtasks.mapIndexed { i, st ->
            st.copy(assignedWorker = workers[i % workers.size], status = SubtaskStatus.Assigned)
        }
    }

    /**
     * V2: runs every shard through the runtime executor **concurrently** and
     * merges the per-shard outputs into a single reply.
     */
    suspend fun runShards(
        taskId: String,
        assigned: List<Subtask>,
        executor: TaskExecutor,
    ): ShardRun {
        store.setTaskState(taskId, TaskState(taskId, status = TaskStatus.Working))
        val results = coroutineScope {
            assigned.map { st ->
                async {
                    val worker = st.assignedWorker ?: AgentId.worker(st.subtaskId)
                    bus.send(AgentMessage.StatusUpdate(st.subtaskId, TaskStatus.Working))
                    store.setTaskState(
                        st.subtaskId,
                        store.getTaskState(st.subtaskId)?.copy(status = TaskStatus.Working)
                            ?: TaskState(st.subtaskId, status = TaskStatus.Working),
                    )
                    try {
                        var attempt = 0
                        var output = ""
                        var lastError: Exception? = null
                        var succeeded = false
                        while (attempt <= config.maxTaskRetries) {
                            try {
                                output = executor.execute(st.instruction)
                                succeeded = true
                                break
                            } catch (e: Exception) {
                                lastError = e
                                if (attempt < config.maxTaskRetries) {
                                    val backoffMs = minOf(
                                        config.retryBaseDelayMs * (1 shl attempt),
                                        config.maxRetryDelayMs,
                                    )
                                    delay(backoffMs)
                                }
                                attempt++
                            }
                        }
                        if (succeeded) {
                            ShardResult(
                                subtaskId = st.subtaskId,
                                instruction = st.instruction,
                                worker = worker,
                                output = output,
                                succeeded = true,
                            )
                        } else {
                            bus.send(AgentMessage.TaskEscalated(st.subtaskId, lastError?.message ?: "Exhausted retries"))
                            ShardResult(
                                subtaskId = st.subtaskId,
                                instruction = st.instruction,
                                worker = worker,
                                output = "",
                                succeeded = false,
                                error = "Escalated: ${lastError?.message ?: "max retries $attempt exhausted"}",
                            )
                        }
                    } catch (e: Exception) {
                        ShardResult(
                            subtaskId = st.subtaskId,
                            instruction = st.instruction,
                            worker = worker,
                            output = "",
                            succeeded = false,
                            error = e.message ?: "Shard failed",
                        )
                    }
                }
            }.awaitAll()
        }
        val run = ShardRun(
            taskId = taskId,
            shards = results,
            allSucceeded = results.all(ShardResult::succeeded),
        )
        store.setTaskState(
            taskId,
            store.getTaskState(taskId)?.copy(
                status = if (run.allSucceeded) TaskStatus.Done else TaskStatus.Failed,
                output = run.mergedOutput,
            ) ?: TaskState(
                taskId,
                status = if (run.allSucceeded) TaskStatus.Done else TaskStatus.Failed,
                output = run.mergedOutput,
            ),
        )
        return run
    }

    fun resetWorkers() {
        workers.clear()
    }

    fun onWorkerStatus(taskId: String, status: TaskStatus) {
        store.setTaskState(taskId, store.getTaskState(taskId)?.copy(status = status) ?: TaskState(taskId, status = status))
    }

    fun inspectMismatch(taskId: String, uiOutput: String, backendOutput: String) {
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
