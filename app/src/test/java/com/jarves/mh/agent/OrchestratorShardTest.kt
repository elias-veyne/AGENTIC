package com.jarves.mh.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorShardTest {

    private fun orchestrator(retries: Int = 0) =
        Orchestrator(MessageBus(), SharedStateStore(), SystemConfig(maxTaskRetries = retries))

    @Test
    fun `assign spreads shards round-robin across workers`() {
        val orchestrator = orchestrator()
        val subtasks = orchestrator.decompose("t1", "build the thing")
        val assigned = orchestrator.assign(subtasks, listOf(AgentId.worker("a"), AgentId.worker("b")))

        assertEquals(2, assigned.size)
        // AgentId.worker(name) namespaces as "worker_<name>".
        assertEquals(listOf("worker_a", "worker_b"), assigned.map { it.assignedWorker?.value })
        assertTrue(assigned.all { it.status == Orchestrator.SubtaskStatus.Assigned })
    }

    @Test
    fun `shards run concurrently and every output is merged`() = runBlocking {
        val orchestrator = orchestrator()
        val assigned = orchestrator.assign(
            orchestrator.decompose("t1", "build the thing"),
            listOf(AgentId.worker("a")),
        )

        val run = orchestrator.runShards("t1", assigned, TaskExecutor { "done" })

        assertTrue(run.allSucceeded)
        assertEquals(2, run.shards.size)
        // Both shard outputs must survive the merge, or a fan-out silently loses work.
        assertEquals(2, run.shards.count { it.output == "done" })
        assertTrue(run.mergedOutput.contains("done"))
    }

    @Test
    fun `a failing shard does not cancel its siblings`() = runBlocking {
        val orchestrator = orchestrator()
        val assigned = orchestrator.assign(
            orchestrator.decompose("t1", "build the thing"),
            listOf(AgentId.worker("a")),
        )

        var calls = 0
        val run = orchestrator.runShards("t1", assigned) {
            calls++
            if (calls == 1) throw IllegalStateException("shard exploded") else "ok"
        }

        assertFalse(run.allSucceeded)
        assertEquals(1, run.shards.count { !it.succeeded })
        assertEquals(1, run.shards.count { it.succeeded })
    }

    @Test
    fun `task state is marked done only when every shard succeeds`() = runBlocking {
        val store = SharedStateStore()
        val orchestrator = Orchestrator(MessageBus(), store, SystemConfig(maxTaskRetries = 0))
        val assigned = orchestrator.assign(
            orchestrator.decompose("t1", "build the thing"),
            listOf(AgentId.worker("a")),
        )

        orchestrator.runShards("t1", assigned, TaskExecutor { "ok" })

        assertEquals(TaskStatus.Done, store.getTaskState("t1")?.status)
    }
}

class HeartbeatMonitorTest {

    @Test
    fun `a silent agent crosses the failure threshold`() = runBlocking {
        val monitor = HeartbeatMonitor(intervalMs = 1_000, failuresThreshold = 3)
        val agent = AgentId.worker("sub1")
        monitor.heartbeat(agent)

        // Silence for interval * threshold must register a strike.
        repeat(3) {
            monitor.checkHealth(agent, lastSeen = System.currentTimeMillis() - 10_000)
        }

        assertEquals(3, monitor.retryCount(agent))
    }

    @Test
    fun `a beating agent is never failed`() = runBlocking {
        val monitor = HeartbeatMonitor(intervalMs = 1_000, failuresThreshold = 3)
        val agent = AgentId.worker("sub1")
        monitor.heartbeat(agent)

        monitor.checkHealth(agent, lastSeen = System.currentTimeMillis())

        assertEquals(0, monitor.retryCount(agent))
    }

    @Test
    fun `a heartbeat resets an existing strike`() = runBlocking {
        val monitor = HeartbeatMonitor(intervalMs = 1_000, failuresThreshold = 3)
        val agent = AgentId.worker("sub1")
        monitor.heartbeat(agent)
        monitor.checkHealth(agent, lastSeen = System.currentTimeMillis() - 10_000)
        assertTrue(monitor.retryCount(agent) > 0)

        monitor.heartbeat(agent)

        assertEquals(0, monitor.retryCount(agent))
    }

    @Test
    fun `a retired agent is no longer watched`() = runBlocking {
        val monitor = HeartbeatMonitor(intervalMs = 1_000, failuresThreshold = 3)
        val agent = AgentId.worker("sub1")
        monitor.heartbeat(agent)
        monitor.retire(agent)

        // Retiring clears bookkeeping, so later silence cannot resurrect the agent.
        monitor.checkHealth(agent, lastSeen = System.currentTimeMillis() - 10_000)

        assertEquals(0, monitor.retryCount(agent))
    }
}