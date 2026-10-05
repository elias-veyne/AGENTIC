package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.dshApiForProfile
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) over its
 * newline-delimited JSON-RPC SDK protocol (`dsh --profile sdk`).
 *
 * Verified contract: the final answer streams as `assistant/chunk` deltas,
 * tool calls arrive as `tool/call` + `tool/result`, a turn ends with
 * `turn/end`, and failures surface as a JSON-RPC error or a `dsh: <CODE>`
 * diagnostic. Because the native launcher merges stdout+stderr into one
 * capture file, the parser treats a `dsh:` prefix as a failure signal.
 *
 * Auth and model selection are fully non-interactive: keys travel in the
 * process environment (`DEEPSEEK_API_KEY` for the native route, one shared
 * `MH_DSH_API_KEY` for hand-declared routes) and `$DSH_HOME/settings.yaml`
 * carries the default model plus any custom provider route.
 */
class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    /** Current GitHub PAT, pushed from the view model so the agent can reach
     *  private repos and push. Updated via [updateGithubToken]. */
    @Volatile private var githubToken: String? = null

    fun updateGithubToken(token: String?) {
        val normalized = token?.takeIf { it.isNotBlank() }
        githubToken = normalized
        // Non-blocking: a conflated channel always keeps the most recent value. The
        // single consumer drains it in order, so a rapid paste-then-clear cannot
        // leave the cleared token on disk the way unordered IO launches could.
        credentialWrites.trySend(normalized)
    }

    /**
     * Materializes the GitHub PAT inside the guest Linux home. Injecting it as
     * an env var at [startSession] only helps sessions spawned *after* the user
     * pastes it; a session already running — the common case, since the user
     * adds the token mid-conversation — would never see it. Writing it to disk
     * means the running agent can pick it up immediately, `git` authenticates
     * through a credential helper instead of needing the env var, and the
     * token leaves the guest again when the user clears it.
     */
    private fun writeGithubCredential(token: String?) {
        val rootfs = runCatching { installer.installedRuntime().rootfs }.getOrNull() ?: return
        val secretFile = File(rootfs, "root/.secrets/github-token")
        val helperFile = File(rootfs, "usr/local/bin/git-credential-pocket")
        val credFile = File(rootfs, "root/.pocket-credentials.gitconfig")
        val gitconfig = File(rootfs, "root/.gitconfig")
        // One self-contained block so teardown removes exactly what we added.
        // Inside an [include] section the key is `path`, resolved relative to
        // this config file's own directory (i.e. /root).
        val block = buildString {
            appendLine(GITCONFIG_MARKER)
            appendLine("[include]")
            append("\tpath = ${credFile.name}\n")
        }

        if (token.isNullOrBlank()) {
            secretFile.delete()
            helperFile.delete()
            credFile.delete()
            val current = runCatching { gitconfig.readText() }.getOrDefault("")
            if (block in current) {
                val without = current.replace(block, "")
                if (without.isBlank()) gitconfig.delete() else gitconfig.writeText(without)
            }
            return
        }

        File(rootfs, "root/.secrets").mkdirs()
        secretFile.writeText(token)
        helperFile.writeText(
            """
            #!/bin/sh
            # Written by Agentic: hands the saved GitHub PAT to git on request.
            # Never echoes the token to the terminal or into shell history.
            case "${'$'}1" in
            get)
              token=$(cat /root/.secrets/github-token 2>/dev/null) || exit 0
              [ -n "${'$'}token" ] || exit 0
              echo "protocol=https"
              echo "host=github.com"
              echo "username=x-access-token"
              echo "password=${'$'}token"
              ;;
            esac
            exit 0
            """.trimIndent()
        )
        helperFile.setExecutable(true, false)
        // `git` resolves `helper = pocket` by searching PATH for
        // git-credential-pocket, which /usr/local/bin provides.
        credFile.writeText("$GITCONFIG_MARKER\n[credential]\n\thelper = pocket\n")
        val existing = runCatching { gitconfig.readText() }.getOrDefault("")
        if (block !in existing) {
            val prefix = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
            gitconfig.appendText(prefix + block)
        }
    }

    private val installer = RuntimeInstaller(context)
    /** Off-main-thread home for [writeGithubCredential]; a failing write must
     *  never cancel an in-flight credential update. */
    private val credentialScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Serial, latest-value-wins sink for credential updates. Plain launches onto
     * [Dispatchers.IO] carry no ordering guarantee, so a rapid paste-then-clear could
     * persist the token the user had just removed. A conflated channel drained by a
     * single consumer applies updates in order and always ends on the most recent one.
     */
    private val credentialWrites = Channel<String?>(Channel.CONFLATED)

    init {
        credentialScope.launch { credentialWrites.consumeEach { token -> writeGithubCredential(token) } }
    }
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    // SUSPEND (the default overflow policy) is deliberate: this bus carries terminal
    // lifecycle events and streaming AssistantDeltas, and the UI accumulates reply text
    // from those deltas, so a dropped event would lose text or leave the chat stuck in
    // its running state. The reader loop emits from Dispatchers.IO while the sole
    // collector runs on Main, so a slow frame can otherwise suspend the reader and stall
    // the agent. A generous buffer absorbs Main-thread pauses without dropping anything.
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 512)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runtime state for one session. Sessions genuinely overlap — Agentic shards
     * run concurrently, cooperative peers start alongside the head, and a
     * key-failure retry can fire while another session is still alive — so the
     * process handle, stop flag, and foreground bookkeeping live per session
     * instead of in single shared fields that two sessions would clobber.
     */
    private class SessionState(val projectSlug: String, val startedAtElapsedRealtime: Long) {
        @Volatile var process: Process? = null
        @Volatile var userStopRequested: Boolean = false
        @Volatile var lastForegroundProgressAt: Long = 0L
        @Volatile var foregroundResultPosted: Boolean = false
        @Volatile var lastThinkingUpdateAt: Long = 0L
        /**
         * True once this session hit the inactivity ceiling and was killed. Reported as a
         * failure (never a user stop) so the UI explains itself instead of appearing to hang
         * until the user gives up and taps stop.
         */
        @Volatile var timedOut: Boolean = false
        /** Last time the harness produced bytes; drives the inactivity check. */
        @Volatile var lastOutputAtElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime()
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile, resolvedSecret: String?, sessionId: String?): String = withContext(Dispatchers.IO + NonCancellable) {
        // Honour a caller-supplied id. This function blocks until the run finishes, so an
        // id learned from the return value could never match the events emitted during the
        // run: every comparison would be made against null and silently fail.
        val sessionId = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        val state = SessionState(
            projectSlug = projectSlug,
            startedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
        )
        sessions[sessionId] = state
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress(sessionId, "Starting DeepSeek Harness…")
        val secret = resolvedSecret?.takeIf { it.isNotBlank() } ?: secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }

        var sessionHome: File? = null
        var stopToken: (() -> Unit)? = null
        runCatching {
            stopToken = RuntimeTaskController.registerStopAction {
                state.userStopRequested = true
                val running = state.process
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(sessionId, projectSlug)
            val installed = installer.installedRuntime()
            // A PAT saved before the runtime existed never reached disk; the
            // write bailed on the missing rootfs. Re-apply now that it is here.
            writeGithubCredential(githubToken)
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val route = DshRouteMapper.forProfile(provider)
            sessionHome = writeDshSettings(installed.rootfs, sessionId, route, provider)
            val environment = linkedMapOf(
                // Each session gets its own dsh home so overlapping sessions on
                // different providers never race on one shared settings.yaml.
                "DSH_HOME" to dshHomeGuestPath(sessionId),
                // PocketDev already confines the whole Linux guest with PRoot. Let dsh
                // use every tool inside that boundary without an unavailable approval UI.
                "DSH_PERMISSION_MODE" to "danger-full-access",
                route.keyEnv to secret,
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)
            // Give the agent the GitHub PAT so it can clone private repos and
            // push. `gh` and `git` both read GITHUB_TOKEN; git also accepts it
            // as an https credential via the credential helper below.
            githubToken?.takeIf { it.isNotBlank() }?.let { token ->
                environment["GITHUB_TOKEN"] = token
                environment["GH_TOKEN"] = token
            }

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                // dsh's editor saves through an atomic temp-file rename. PRoot's
                // hard-link emulation turns that rename into a dangling `.l2s`
                // symlink after the temp file is removed, losing the real file.
                emulateHardLinks = false,
            )
            state.process = process
            if (state.userStopRequested) process.destroy()
            val sdkResult = runSdkSession(
                process = process,
                sessionId = sessionId,
                route = route,
                model = provider.model.ifBlank { route.defaultModel },
                guestWorkspacePath = guestWorkspacePath,
                prompt = contextPrompt,
            )
            val exit = process.waitFor()
            Log.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("DshBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            if (exit == 0 && sdkResult.completed && !state.userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    sessionId = sessionId,
                    completed = true,
                    projectName = projectSlug,
                    detail = "DeepSeek Harness finished the task in $projectSlug.",
                )
            } else {
                if (state.userStopRequested) throw DshSessionException("Stopped by user")
                error(
                    sdkResult.failure.ifBlank {
                        if (state.timedOut) {
                            "DeepSeek Harness stopped responding for " +
                                "${SESSION_INACTIVITY_TIMEOUT_MS / 60_000} minutes and was stopped."
                        } else {
                            "DeepSeek Harness stopped with exit code $exit"
                        }
                    },
                )
            }
        }.onFailure { error ->
            if (state.userStopRequested) {
                // A deliberate stop is reported as a stop, never as a failure. The old
                // path emitted SessionFailed("Stopped by user") and relied on the
                // ViewModel's substring filters to keep it from looking like a key rejection.
                Log.d("DshBridge", "Session stopped by user")
                emitStoppedOnce(sessionId)
            } else {
                Log.e("DshBridge", "Session failed", error)
                val message = friendlyError(error)
                emitFailureOnce(sessionId, message)
            }
        }
        // The dsh process has exited by now, so its per-session home is dead config.
        sessionHome?.let { runCatching { it.deleteRecursively() } }
        sessions.remove(sessionId)
        stopToken?.invoke()
        sessionId
    }

    private suspend fun runSdkSession(
        process: Process,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val writer = process.outputStream.bufferedWriter()
        val parser = DshSdkProtocolParser(sessionId)
        var outputOffset = 0L
        // The protocol is newline-framed UTF-8, so incomplete reads must be buffered at
        // the BYTE level and only decoded up to the last complete newline. Decoding each
        // 16 KB chunk independently would split a multibyte sequence at the read boundary
        // and decodeToString() would replace the orphaned continuation bytes with U+FFFD,
        // corrupting the entire line and silently dropping a protocol message.
        val pendingBytes = java.io.ByteArrayOutputStream()
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var shutdownSent = false
        var shutdownSentAt = 0L
        var inputClosed = false
        var failure = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { writer.close() }
        }

        send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = JSONObject()
                .put("cwd", guestWorkspacePath)
                .put("provider", route.name)
                .put("model", model),
        )

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/prompt",
                        id = SDK_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                            ),
                    )
                    promptSent = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress(sessionId, "DeepSeek Harness is working…")
                    } else if (sawRunning && !shutdownSent) {
                        completed = sawActivity && failure.isBlank()
                        if (!completed && failure.isBlank()) {
                            failure = "DeepSeek Harness stopped before processing the prompt"
                        }
                        shutdownSent = true
                        shutdownSentAt = android.os.SystemClock.elapsedRealtime()
                        // Only a live process can acknowledge a shutdown frame. The final
                        // flush after process death can still deliver the harness's last
                        // status line; writing to a dead pipe would throw IOException out
                        // of runSdkSession and flip a completed session to failed.
                        if (process.isAlive) send("shutdown", SDK_SHUTDOWN_ID)
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                    )
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    sawActivity = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                }
                is DshSdkProtocolEvent.Failed -> failure = protocolEvent.message
                DshSdkProtocolEvent.TurnCompleted -> sawActivity = true
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
            if (
                process.isAlive &&
                shutdownSentAt > 0L &&
                android.os.SystemClock.elapsedRealtime() - shutdownSentAt >= SDK_SHUTDOWN_TIMEOUT_MS
            ) {
                closeInput()
                process.destroy()
            }
            // Inactivity ceiling. Without this the loop's only exit was process death, so a
            // dsh that stays alive but stops talking (stalled endpoint, unanswered request,
            // unrecognised protocol frame) hung with zero terminal events and the UI spun
            // forever. The one timer above is armed only after a running->idle status, which
            // never arrives in that case, so this is the only bound on the wait.
            if (process.isAlive && shutdownSentAt == 0L) {
                val silentMs = android.os.SystemClock.elapsedRealtime() - state.lastOutputAtElapsedRealtime
                if (silentMs >= SESSION_INACTIVITY_TIMEOUT_MS) {
                    state.timedOut = true
                    closeInput()
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                    break
                }
            }
            val available = nativeProcess.outputFile.length() - outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(outputOffset)
                file.read(bytes)
            }
            // count <= 0 with bytes still reported means no forward progress; yield so the
            // loop cannot spin at 100% CPU waiting on the file to catch up.
            if (count <= 0) {
                delay(50)
                continue
            }
            state.lastOutputAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
            outputOffset += count
            pendingBytes.write(bytes, 0, count)

            // Decode only the fully-received lines, keeping any partial trailing bytes.
            // A '\n' (0x0A) is a single-byte ASCII character and can never appear inside
            // a UTF-8 multibyte sequence, so splitting on the last newline always lands
            // on a sequence boundary.
            val buffer = pendingBytes.toByteArray()
            val lastNewline = buffer.indexOfLast { it == NEWLINE_BYTE }
            if (lastNewline < 0) continue
            val text = buffer.decodeToString(0, lastNewline + 1)
            pendingBytes.reset()
            pendingBytes.write(buffer, lastNewline + 1, buffer.size - lastNewline - 1)
            var newline = text.indexOf("\n")
            while (newline >= 0) {
                val line = text.substring(0, newline).trimEnd('\r')
                if (line.isNotBlank()) handle(parser.parseLine(line))
                newline = text.indexOf("\n", newline + 1)
            }
        }
        pendingBytes.toByteArray().decodeToString().trim().takeIf(String::isNotBlank)?.let {
            handle(parser.parseLine(it))
        }
        closeInput()
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless one-shot runs expose no approval channel; nothing is ever requested.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        val state = sessions[sessionId] ?: return@withContext
        state.userStopRequested = true
        state.process?.destroy()
        delay(500)
        if (state.process?.isAlive == true) state.process?.destroyForcibly()
        // A successful, user-initiated stop is a first-class terminal state — not a
        // failure. Emitting SessionStopped here keeps the ViewModel from having to
        // pattern-match on the message prose to decide how to react.
        emitStoppedOnce(sessionId)
    }

    override suspend fun stopActiveSession() {
        // Shards and cooperative peers run alongside the head, so a global "stop"
        // must reach every live session rather than just whichever started last.
        sessions.keys.toList().forEach { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun writeDshSettings(rootfs: File, sessionId: String, route: DshRoute, provider: ProviderProfile): File {
        val home = File(rootfs, dshHomeGuestPath(sessionId).removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("agent-default-model:")
            appendLine("  provider: ${route.name}")
            appendLine("  model: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            if (route.custom != null) {
                appendLine("llm-pi-ai:")
                appendLine("  providers:")
                appendLine("    ${route.name}:")
                appendLine("      apiKeyEnv: ${route.keyEnv}")
                appendLine("      api: ${route.custom.api}")
                appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                appendLine("      models:")
                appendLine("        - id: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            }
        }
        File(home, "settings.yaml").writeText(body)
        return home
    }

    /**
     * Per-session dsh home inside the shared rootfs. `$DSH_HOME` is already an
     * environment variable, so isolating the config is just a matter of naming a
     * directory per session — the rootfs itself stays shared and read-only-ish.
     */
    private fun dshHomeGuestPath(sessionId: String): String {
        val suffix = sessionId.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(64)
        return "/root/.dsh-$suffix"
    }

    private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val state = sessions[sessionId] ?: return
        if (force || now - state.lastThinkingUpdateAt >= 400) {
            state.lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = blockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress(sessionId, "Thinking…")
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                sessionId = sessionId,
                completed = true,
                projectName = sessions[sessionId]?.projectSlug ?: "your project",
                detail = "DeepSeek Harness finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            finishForegroundRuntime(
                sessionId = sessionId,
                completed = false,
                projectName = sessions[sessionId]?.projectSlug ?: "your project",
                detail = reason,
            )
        }
    }

    /**
     * Terminal state for a deliberate stop. Distinct from [emitFailureOnce] so a stop is
     * never reported through the failure channel (see [RuntimeEvent.SessionStopped]).
     * [finishedSessions] is shared with [emitFailureOnce] and [emitCompletedOnce], so a
     * stop and a natural completion can never both fire for one session.
     */
    private suspend fun emitStoppedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionStopped(sessionId))
            cancelForegroundRuntime(sessionId)
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                // Word-bounded so a bare status-code substring inside unrelated output
                // — "port 4010", "line 401 of build.gradle" — cannot be misread as an
                // auth rejection and trigger an unnecessary key rotation.
                (HTTP_STATUS_CODE.containsMatchIn(message) &&
                    (message.contains("auth", true) || message.contains("HTTP", true))) ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    /**
     * Truthful capability statement for the harness.
     *
     * The harness owns its own tool set (bash/read/write/edit/glob/grep) and exposes no
     * delegation primitive. Without this the model pattern-matches Claude Code / OpenCode
     * behaviour, *narrates* "launching two subagents now", and then stalls — the user sees
     * a promise of fan-out followed by silence, because there is no tool behind the claim.
     * Stating the boundary explicitly makes the model either do the work inline or ask the
     * user to start an Agentic chat, which is a path that actually fans out.
     */
    private fun capabilitiesPrompt(projectKind: ProjectKind): String = buildString {
        appendLine("<available_tools>")
        appendLine("Your ONLY tools in this chat are: Bash (run shell commands), Read, Write, Edit, Glob, and Grep.")
        appendLine("You have NO task/subagent/delegation tool. Do not claim you are spawning subagents, parallel workers, or background agents — nothing would run.")
        appendLine("To fan work out across parallel agents, the user must start a new chat in Agentic mode from the mode picker. Say that plainly instead of pretending to delegate.")
        appendLine("Do the work yourself with the tools above, splitting long work into several Bash/Write/Edit calls.")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            appendLine("This is a lightweight workspace: keep every command and file inside it.")
        }
        appendLine("</available_tools>")
        appendLine()
    }

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        val priorMessages = history
            .filter { msg ->
                // The error-banner filters strip generated text (assistant error banners
                // and the demo prompt). They must never apply to the user's own messages:
                // a turn that legitimately begins with "Failed to" or "Error:" — or that
                // quotes an "API Error" — would otherwise be dropped from the replay and
                // the agent would lose that turn's context.
                msg.fromUser || (
                    !msg.text.startsWith("Hi! Tell me") &&
                        !msg.text.startsWith("Failed to") &&
                        !msg.text.startsWith("Error:") &&
                        !msg.text.contains("API Error")
                    )
            }
            .dropLast(1)

        val sb = StringBuilder()
        sb.append(capabilitiesPrompt(projectKind))
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else if (com.jarves.mh.BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        } else {
            sb.appendLine("This online build ships without the Android toolchain — it is ~570 MB and on-device builds are rarely the point. You may create and edit Android project files, but do not claim that Gradle, the Android SDK, aapt2, or any on-device build is available, and never present a local build or install command as verified.")
            sb.appendLine("To produce an APK, write a GitHub Actions workflow that builds and signs the release, commit it, and let CI produce the artifact the user can then download and install. Say clearly that the build happens on CI, not on this phone.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        // The model's training data lags reality; ground it in the real date and
        // give it an explicit fetch path so "latest" questions stop answering
        // from the training cutoff.
        sb.appendLine("Today is ${java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US).format(java.util.Date())}. Your knowledge has a cutoff, so for anything current (news, releases, prices, status) do not rely on memory — fetch it.")
        sb.appendLine("You have `curl` and `wget` in the terminal with network access. For current information, fetch a real source (e.g. `curl -sL https://en.wikipedia.org/wiki/Special:Random` is not a search; prefer the source's own page or a plain-text news endpoint) and cite what you read. If a fetch fails or you cannot verify, say so instead of guessing from memory.")
        githubToken?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("A GitHub personal access token is available — the user has authorized it for private repos, pushes, repo creation, and PRs. Read it with `cat /root/.secrets/github-token` (it is also exported as \$GITHUB_TOKEN / \$GH_TOKEN if this session started after you saved it). `git` already authenticates through the configured credential helper, so `git clone https://github.com/owner/repo` works for private repos with no extra flags; run `gh auth login --with-token < /root/.secrets/github-token` once if you need the `gh` CLI for repo creation and PRs. Never echo the raw token back to the user.")
        }
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun pushForegroundProgress(sessionId: String, detailRaw: String) {
        val state = sessions[sessionId] ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - state.lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        state.lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val text = "$detail · ${formatElapsedShort(now - state.startedAtElapsedRealtime)}"
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, state.projectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(sessionId: String, projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(sessionId: String, completed: Boolean, projectName: String, detail: String) {
        val state = sessions[sessionId]
        if (state?.foregroundResultPosted == true) return
        state?.foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("DshBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime(sessionId: String) {
        val state = sessions[sessionId]
        if (state?.foregroundResultPosted == true) return
        state?.foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        const val GITCONFIG_MARKER = "# managed by Agentic"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L

        /**
         * How long a live harness may stay completely silent before the session is failed.
         * A long turn legitimately produces no bytes for a while, so this must exceed normal
         * think time; it exists to convert an unbounded hang into a reported error.
         */
        private const val SESSION_INACTIVITY_TIMEOUT_MS = 10 * 60_000L
        private val NEWLINE_BYTE: Byte = 0x0A
        private val HTTP_STATUS_CODE = Regex("\\b(?:401|403|429)\\b")
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    /**
     * Zen's Anthropic-wire models are served from the `/zen` root — the Anthropic
     * SDK appends `/v1/messages`, yielding `https://opencode.ai/zen/v1/messages`.
     * Every other wire (responses, chat/completions, google) hangs off `/zen/v1`.
     * Confirmed live: `/zen/v1/messages` answers 401 while `/zen/v1/v1/messages`
     * answers 404, and pi-ai's own catalog records `https://opencode.ai/zen` as
     * the base for its anthropic-messages Zen models.
     */
    private fun zenBaseUrlFor(api: String): String =
        if (api == "anthropic-messages") ZEN_ANTHROPIC_BASE_URL else ProviderKind.OPENCODE_ZEN.defaultBaseUrl

    private const val ZEN_ANTHROPIC_BASE_URL = "https://opencode.ai/zen"

    fun forProfile(profile: ProviderProfile): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> {
                // Zen serves each model family on its own wire endpoint, so both
                // the protocol and the base URL resolve from the model.
                val api = dshApiForProfile(profile.kind, profile.model, profile.dshApi)
                DshRoute(
                    name = "opencode-zen",
                    keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                    defaultModel = model,
                    custom = DshCustomRoute(api, zenBaseUrlFor(api)),
                )
            }
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> DshRoute(
                name = "mh-custom",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
        }
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private val streamedTextSinceMessage = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(line.removePrefix("dsh:").trim())
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").ifBlank { "DeepSeek Harness SDK request $id failed" },
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = contentText(content)
                if (text.isBlank()) {
                    DshSdkProtocolEvent.Ignored
                } else if (streamedTextSinceMessage.isNotEmpty()) {
                    // `assistant/message` repeats the completed content after the SDK has
                    // already delivered its text deltas. The UI has appended those deltas.
                    streamedTextSinceMessage.clear()
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.AssistantText(text)
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.optString("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("message").orEmpty()
                    .ifBlank { text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .ifBlank { "DeepSeek Harness turn failed" },
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }.append(delta)
                streamedTextSinceMessage.append(delta)
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.append(chunk.optString("text"))
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = complete.takeIf { it.startsWith(streamed) }?.removePrefix(streamed).orEmpty()
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage.append(missingSuffix)
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text").ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return detail.ifBlank { arguments }.replace(Regex("\\s+"), " ").trim().take(240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        return buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    "tool-result" -> contentText(block.optJSONArray("content")).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.joinToString("\n")
    }
}
