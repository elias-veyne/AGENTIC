package com.jarves.mh.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the PRD's classification table and the escalation rules around it.
 *
 * These run on the JVM with no device and no network: the router is deliberately free
 * of Android and SQLite so the whole routing surface is testable in CI in seconds.
 */
class OrchestrationRouterTest {

    private fun router(localCoreAvailable: Boolean = true) =
        OrchestrationRouter(policy = OrchestrationRouter.Policy(localCoreAvailable = localCoreAvailable))

    // --- the PRD table ------------------------------------------------------

    @Test
    fun `phone control routes local`() {
        assertEquals(
            OrchestrationRouter.Route.LOCAL_ONLY,
            router().route("send a text to Alex saying I'll be late"),
        )
    }

    @Test
    fun `short chat routes local`() {
        assertEquals(
            OrchestrationRouter.Route.LOCAL_ONLY,
            router().route("what is the capital of France"),
        )
    }

    @Test
    fun `multi-step device work routes hybrid`() {
        assertEquals(
            OrchestrationRouter.Route.HYBRID,
            router().route("open settings then turn on airplane mode"),
        )
    }

    @Test
    fun `repo work routes remote`() {
        assertEquals(
            OrchestrationRouter.Route.REMOTE_ONLY,
            router().route("refactor the repository and open a pull request"),
        )
    }

    @Test
    fun `an explicit cloud request routes remote`() {
        assertEquals(
            OrchestrationRouter.Route.REMOTE_ONLY,
            router().route("use GPT for this, heavy thinking needed"),
        )
    }

    @Test
    fun `every classification maps to the route the table specifies`() {
        // Guards the whole enum in one place, so adding a RequestKind cannot quietly
        // fall through to the wrong branch.
        val expected = mapOf(
            OrchestrationRouter.RequestKind.DEVICE_CONTROL to OrchestrationRouter.Route.LOCAL_ONLY,
            OrchestrationRouter.RequestKind.SHORT_CHAT to OrchestrationRouter.Route.LOCAL_ONLY,
            OrchestrationRouter.RequestKind.AMBIGUOUS_DEVICE to OrchestrationRouter.Route.HYBRID,
            OrchestrationRouter.RequestKind.HEAVY_CODE to OrchestrationRouter.Route.REMOTE_ONLY,
            OrchestrationRouter.RequestKind.EXPLICIT_CLOUD to OrchestrationRouter.Route.REMOTE_ONLY,
        )
        val r = router()
        expected.forEach { (kind, route) -> assertEquals("kind=$kind", route, r.routeFor(kind)) }
    }

    // --- precedence ---------------------------------------------------------

    @Test
    fun `an explicit cloud request overrides device control`() {
        // The user naming a provider is deliberate. Honouring the device rule instead
        // would substitute our judgement for theirs, and it is the one rule a user can
        // be expected to apply knowingly.
        assertEquals(
            OrchestrationRouter.RequestKind.EXPLICIT_CLOUD,
            OrchestrationRouter.defaultClassifier("use gpt to draft a message I will send a text with"),
        )
    }

    // --- regressions --------------------------------------------------------

    @Test
    fun `the word context does not classify as device control`() {
        // "text " is a substring of "context ". With that marker in the list, any
        // instruction mentioning a context window was pinned local as device control.
        assertEquals(
            OrchestrationRouter.RequestKind.SHORT_CHAT,
            OrchestrationRouter.defaultClassifier("summarise the context window setting"),
        )
    }

    @Test
    fun `the word cloud alone does not request the cloud`() {
        // "cloud storage" must not read as "ask the cloud agent".
        assertNotEquals(
            OrchestrationRouter.RequestKind.EXPLICIT_CLOUD,
            OrchestrationRouter.defaultClassifier("open my cloud storage app"),
        )
    }

    @Test
    fun `classification is case insensitive`() {
        assertEquals(
            OrchestrationRouter.RequestKind.EXPLICIT_CLOUD,
            OrchestrationRouter.defaultClassifier("USE GPT for this"),
        )
    }

    // --- escalation policy --------------------------------------------------

    @Test
    fun `device control never escalates even on an unavailable model`() {
        // The privacy guarantee. Escalating device control would send the contact
        // name or screenshot that made it device-bound in the first place.
        val r = router()
        assertFalse(
            r.mayEscalate(
                OrchestrationRouter.RequestKind.DEVICE_CONTROL,
                OrchestrationRouter.LocalFailure.Unavailable("oom"),
            )
        )
    }

    @Test
    fun `multi-step device work never escalates`() {
        val r = router()
        assertFalse(
            r.mayEscalate(
                OrchestrationRouter.RequestKind.AMBIGUOUS_DEVICE,
                OrchestrationRouter.LocalFailure.Unavailable("oom"),
            )
        )
    }

    @Test
    fun `short chat escalates on an unavailable model`() {
        // Local by preference, not by content. A dead local model is exactly when the
        // fallback is meant to engage.
        val r = router()
        assertTrue(
            r.mayEscalate(
                OrchestrationRouter.RequestKind.SHORT_CHAT,
                OrchestrationRouter.LocalFailure.Unavailable("model load failed"),
            )
        )
    }

    @Test
    fun `nothing escalates on a fatal failure`() {
        // Otherwise a local bug becomes silent egress of whatever the user typed.
        val r = router()
        OrchestrationRouter.RequestKind.entries.forEach { kind ->
            assertFalse("kind=$kind", r.mayEscalate(kind, OrchestrationRouter.LocalFailure.Fatal("malformed prompt")))
        }
    }

    @Test
    fun `isDeviceBound covers exactly the device-touching kinds`() {
        val r = router()
        assertTrue(r.isDeviceBound(OrchestrationRouter.RequestKind.DEVICE_CONTROL))
        assertTrue(r.isDeviceBound(OrchestrationRouter.RequestKind.AMBIGUOUS_DEVICE))
        assertFalse(r.isDeviceBound(OrchestrationRouter.RequestKind.SHORT_CHAT))
        assertFalse(r.isDeviceBound(OrchestrationRouter.RequestKind.HEAVY_CODE))
        assertFalse(r.isDeviceBound(OrchestrationRouter.RequestKind.EXPLICIT_CLOUD))
    }

    // --- reporting when no local core is loaded -----------------------------

    @Test
    fun `chat reports the fallback route when no local core is loaded`() {
        // The UI must be able to say "this may go to the cloud" before it happens.
        assertEquals(
            OrchestrationRouter.Route.LOCAL_PREFERRED_REMOTE_FALLBACK,
            router(localCoreAvailable = false).route("what is the capital of France"),
        )
    }

    @Test
    fun `device control reports no fallback when no local core is loaded`() {
        // There is nothing to fall back to that would be safe, so the honest report
        // is LOCAL_ONLY and the attempt fails rather than being dispatched.
        assertEquals(
            OrchestrationRouter.Route.LOCAL_ONLY,
            router(localCoreAvailable = false).route("send a text to Alex"),
        )
    }

    @Test
    fun `a remote route is unaffected by the local core being unavailable`() {
        assertEquals(
            OrchestrationRouter.Route.REMOTE_ONLY,
            router(localCoreAvailable = false).route("refactor the repository"),
        )
    }

    @Test
    fun `classification still runs when no local core is loaded`() {
        // Routing is affected, but the kind is still reported so the UI and telemetry
        // can say what the request would have been.
        assertEquals(
            OrchestrationRouter.RequestKind.DEVICE_CONTROL,
            router(localCoreAvailable = false).classify("send a text to Alex"),
        )
    }
}

/** Exercises the executor that actually dispatches, with both cores faked. */
class RoutingTaskExecutorTest {

    private val remoteCalls = mutableListOf<String>()
    private val localCalls = mutableListOf<String>()

    private val remote = TaskExecutor { instruction ->
        remoteCalls += instruction
        "remote:$instruction"
    }

    private val localOk = TaskExecutor { instruction ->
        localCalls += instruction
        "local:$instruction"
    }

    private fun localFailing(failure: OrchestrationRouter.LocalFailure) = TaskExecutor { _ ->
        localCalls += "attempted"
        throw LocalExecutionException(failure)
    }

    private fun executor(local: TaskExecutor?, telemetry: RouteTelemetry = NoRouteTelemetry) =
        RoutingTaskExecutor(OrchestrationRouter(), local, remote, telemetry)

    @Test
    fun `a remote only request never touches the local core`() = runBlocking {
        val out = executor(localOk).execute("refactor the repository")

        assertEquals("remote:refactor the repository", out)
        assertTrue("local must not be called for a remote route", localCalls.isEmpty())
    }

    @Test
    fun `a chat request runs on the local core`() = runBlocking {
        val out = executor(localOk).execute("what is the capital of France")

        assertEquals("local:what is the capital of France", out)
        assertTrue(remoteCalls.isEmpty())
    }

    @Test
    fun `a hybrid request stays local end to end`() = runBlocking {
        val out = executor(localOk).execute("open settings then turn on airplane mode")

        assertEquals("local:open settings then turn on airplane mode", out)
        assertTrue("hybrid must not make a remote hop", remoteCalls.isEmpty())
    }

    @Test
    fun `an unavailable local model escalates a chat request`() = runBlocking {
        val out = executor(localFailing(OrchestrationRouter.LocalFailure.Unavailable("model load failed")))
            .execute("what is the capital of France")

        assertEquals("remote:what is the capital of France", out)
        assertEquals(1, localCalls.size)
        assertEquals(1, remoteCalls.size)
    }

    @Test
    fun `an unavailable local model does not escalate device control`() = runBlocking {
        var thrown: LocalExecutionException? = null
        try {
            executor(localFailing(OrchestrationRouter.LocalFailure.Unavailable("model load failed")))
                .execute("send a text to Alex saying I'll be late")
        } catch (e: LocalExecutionException) {
            thrown = e
        }

        assertTrue("expected the failure to surface", thrown != null)
        assertTrue("device content must never reach a provider", remoteCalls.isEmpty())
    }

    @Test
    fun `a fatal local failure never escalates`() = runBlocking {
        var thrown: LocalExecutionException? = null
        try {
            executor(localFailing(OrchestrationRouter.LocalFailure.Fatal("malformed prompt")))
                .execute("what is the capital of France")
        } catch (e: LocalExecutionException) {
            thrown = e
        }

        assertTrue("expected the failure to surface", thrown != null)
        assertTrue(remoteCalls.isEmpty())
    }

    @Test
    fun `no local model installed fails instead of dispatching`() = runBlocking {
        // Not having downloaded a model is a configuration state. Treating it as an
        // escalatable failure would mean a fresh install silently sends device
        // traffic upstream the moment anyone asks it to send a text.
        var thrown: LocalExecutionException? = null
        try {
            executor(local = null).execute("send a text to Alex")
        } catch (e: LocalExecutionException) {
            thrown = e
        }

        assertTrue("expected a LocalExecutionException", thrown != null)
        assertTrue(remoteCalls.isEmpty())
    }

    @Test
    fun `no local model installed also fails chat rather than dispatching`() = runBlocking {
        // Conservative by design: escalation is a response to a model that *died*, not
        // to one that was never there. Otherwise remote-only users would still pay
        // for cloud calls on a request the router reported as local.
        var thrown: LocalExecutionException? = null
        try {
            executor(local = null).execute("what is the capital of France")
        } catch (e: LocalExecutionException) {
            thrown = e
        }

        assertTrue("expected a LocalExecutionException", thrown != null)
        assertTrue(remoteCalls.isEmpty())
    }

    @Test
    fun `an escalation is recorded in telemetry`() = runBlocking {
        val escalations = mutableListOf<OrchestrationRouter.Route>()
        val telemetry = object : RouteTelemetry {
            override fun record(
                instruction: String,
                kind: OrchestrationRouter.RequestKind,
                route: OrchestrationRouter.Route,
            ) = Unit

            override fun recordEscalation(
                route: OrchestrationRouter.Route,
                failure: OrchestrationRouter.LocalFailure,
            ) {
                escalations += route
            }
        }

        executor(
            localFailing(OrchestrationRouter.LocalFailure.Unavailable("model load failed")),
            telemetry,
        ).execute("what is the capital of France")

        assertEquals(listOf(OrchestrationRouter.Route.LOCAL_PREFERRED_REMOTE_FALLBACK), escalations)
    }

    @Test
    fun `telemetry records the kind and the route`() = runBlocking {
        val recorded = mutableListOf<Triple<OrchestrationRouter.RequestKind, OrchestrationRouter.Route, String>>()
        val telemetry = object : RouteTelemetry {
            override fun record(
                instruction: String,
                kind: OrchestrationRouter.RequestKind,
                route: OrchestrationRouter.Route,
            ) {
                recorded += Triple(kind, route, instruction)
            }

            override fun recordEscalation(
                route: OrchestrationRouter.Route,
                failure: OrchestrationRouter.LocalFailure,
            ) = Unit
        }

        executor(localOk, telemetry).execute("refactor the repository")

        assertEquals(1, recorded.size)
        assertEquals(OrchestrationRouter.RequestKind.HEAVY_CODE, recorded[0].first)
        assertEquals(OrchestrationRouter.Route.REMOTE_ONLY, recorded[0].second)
    }

    @Test
    fun `routeFor is inspectable without executing`() {
        // The UI shows the chosen route before the user commits to a send.
        assertEquals(
            OrchestrationRouter.Route.LOCAL_ONLY,
            executor(localOk).routeFor("send a text to Alex"),
        )
        assertTrue(remoteCalls.isEmpty())
        assertTrue(localCalls.isEmpty())
    }
}
