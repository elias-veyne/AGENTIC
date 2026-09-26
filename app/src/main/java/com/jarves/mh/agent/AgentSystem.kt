package com.jarves.mh.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onEach

/**
 * Top-level entry point to the multi-agent engine.
 *
 * [executor] runs a single (sub)task instruction against the real runtime bridge.
 * Tests inject a fake; the app wires the live PRoot session.
 */
class AgentSystem(
    private val executor: TaskExecutor = TaskExecutor { instruction ->
        // Safety default: never silently fake real work. Real callers always
        // supply a runtime-backed executor.
        throw IllegalStateException("AgentSystem has no runtime executor wired for: $instruction")
    },
) {
    private val bus = MessageBus()
    private val store = SharedStateStore()
    private val config = SystemConfig()
    private val heartbeat = HeartbeatMonitor(config.heartbeatIntervalMs, config.heartbeatFailuresThreshold)
    private val orchestrator = Orchestrator(bus, store, config)
    private val cooperative = CooperativeSession(bus, store)
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 100)
    val events: Flow<AgentEvent> = _events.asSharedFlow()

    fun getOrchestrator(): Orchestrator = orchestrator
    fun getCooperative(): CooperativeSession = cooperative
    fun getStore(): SharedStateStore = store
    fun getConfig(): SystemConfig = config

    suspend fun startAgent(agentId: AgentId) {
        store.setAgentState(agentId, AgentState(agentId))
        _events.emit(AgentEvent.AgentStarted(agentId))
    }

    suspend fun stopAgent(agentId: AgentId) {
        _events.emit(AgentEvent.AgentStopped(agentId))
    }

    suspend fun recordHeartbeat(agentId: AgentId) {
        heartbeat.heartbeat(agentId)
    }

    /** Heartbeat monitor used by the recovery/self-healing loop (V4/V5). */
    fun getHeartbeat(): HeartbeatMonitor = heartbeat

    /**
     * V2: agentic run. Decomposes the request, assigns shards round-robin across
     * registered workers, executes all shards **concurrently**, and merges the
     * outputs into one reply. [onOutput] receives the merged output.
     *
     * V4: runs a heartbeat monitor loop that watches each worker's liveness and
     * emits [AgentEvent.AgentFailed] when a worker misses too many heartbeats.
     *
     * V5: shards retry with exponential backoff; a shard that exhausts retries sends
     * [AgentMessage.TaskEscalated] and the run's merged output flags it as failed.
     */
    suspend fun runAgentic(
        taskId: String,
        instruction: String,
        onOutput: suspend (String) -> Unit,
        decomposer: Decomposer = Decomposer.DecomposedChild.Default,
        executor: TaskExecutor = this.executor,
    ) = coroutineScope {
        _events.emit(AgentEvent.TaskSubmitted(taskId))
        val workers = orchestrator.getWorkers().ifEmpty {
            val id = AgentId.worker("ui")
            orchestrator.registerWorker(id)
            listOf(id)
        }

        // V4: heartbeat monitor loop — watches each worker and escalates on silence.
        val heartbeatJob = launch {
            heartbeat.events
                .onEach { event ->
                    when (event) {
                        is HeartbeatEvent.Failed -> {
                            _events.emit(AgentEvent.AgentFailed(event.agentId, "Missed ${event.missedBeats} heartbeats"))
                        }
                        is HeartbeatEvent.Received -> { /* keepalive — no action needed */ }
                    }
                }
                .collect { /* flow stays open until this scope cancels */ }
        }

        val subtasks = orchestrator.decompose(taskId, instruction, decomposer)
        val assigned = orchestrator.assign(subtasks, workers)
        _events.emit(AgentEvent.TaskShardsDispatched(taskId, assigned.size))
        val run = orchestrator.runShards(taskId, assigned, executor)

        heartbeatJob.cancelAndJoin()

        if (run.allSucceeded) {
            _events.emit(AgentEvent.TaskCompleted(taskId, run.mergedOutput))
        } else {
            _events.emit(AgentEvent.TaskFailed(taskId, "One or more shards failed or escalated"))
        }
        onOutput(run.mergedOutput)
    }

    suspend fun continueTask(taskId: String, checkpoint: String, executor: TaskExecutor = this.executor) {
        _events.emit(AgentEvent.TaskResumed(taskId, checkpoint))
        val state = store.getTaskState(taskId)
        if (state != null) {
            val resumed = executor.execute(checkpoint)
            store.setTaskState(taskId, state.copy(output = resumed, status = TaskStatus.Done))
            _events.emit(AgentEvent.TaskCompleted(taskId, resumed))
        }
    }

    suspend fun reportMismatch(taskId: String, contract: String) {
        bus.send(AgentMessage.MismatchAlert(taskId, contract))
    }

    /** Exposes the message bus for recovery/watchdog loops. */
    fun getBus(): MessageBus = bus
}

sealed class AgentEvent {
    data class AgentStarted(val agentId: AgentId) : AgentEvent()
    data class AgentStopped(val agentId: AgentId) : AgentEvent()
    data class TaskSubmitted(val taskId: String) : AgentEvent()
    data class TaskShardsDispatched(val taskId: String, val shardCount: Int) : AgentEvent()
    data class TaskCompleted(val taskId: String, val output: String) : AgentEvent()
    data class TaskResumed(val taskId: String, val checkpoint: String) : AgentEvent()
    data class TaskFailed(val taskId: String, val reason: String) : AgentEvent()
    data class AgentFailed(val agentId: AgentId, val reason: String) : AgentEvent()
    /** V5: a shard exhausted all retries and requires human attention. */
    data class TaskEscalated(val taskId: String, val reason: String) : AgentEvent()
}
