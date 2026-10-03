# Agentic — Code Audit Findings

> **Status:** This is the pre-fix audit as of `19129aa`. Every defect recorded below has since
> been addressed; see **section G — Resolution log** at the end for exactly what changed.
>
> Repo: `elias-veyne/AGENTIC` @ `19129aa` (main). Package `com.jarves.mh`, applicationId `com.veyne.agentic`.
> Scope: full audit. Method: direct read-through of the runtime layer + parallel subagent audits of
> agent-orchestration, UI/ViewModel, and network/data layers.

---

## A. Flagged bug #1 — `stopSession` posts a failure even on a successful stop

**File:** `app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:441-448`

```kotlin
override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
    val state = sessions[sessionId] ?: return@withContext
    state.userStopRequested = true
    state.process?.destroy()
    delay(500)
    if (state.process?.isAlive == true) state.process?.destroyForcibly()
    emitFailureOnce(sessionId, "Stopped by user")   // <-- unconditional failure event
}
```

**Mechanism.** An intentional, successful user stop is reported through the *failure* channel
(`RuntimeEvent.SessionFailed`), not a dedicated "stopped" event. The session's own main flow has a
second failure path for the same condition (`DshRuntimeBridge.kt:262` throws
`DshSessionException("Stopped by user")` → `onFailure` → `emitFailureOnce`). Double emission is
prevented only by the `finishedSessions` set guard inside `emitFailureOnce`
(`DshRuntimeBridge.kt:597-598`).

**Actual impact today — LOW.** I traced every consumer of `SessionFailed`:
- `MainViewModel.retryWithNextApiKey` (`MainViewModel.kt:3967`) is gated on
  `isApiKeyFailure(reason)` (`MainViewModel.kt:4004`). `"stopped by user"` matches none of the
  substrings, so **no API-key rotation / automatic restart happens** on a stop. Good.
- The toast filter (`MainViewModel.kt:3876-3880`) also excludes it, so no error toast.
- The UI renders it as `ActivityItem("Task stopped", "Stopped by user")` — which is arguably the
  desired UX.
- Order race vs `FilesChanged`: `stopSession` emits after a 500 ms `delay`, while the main flow
  emits `FilesChanged` as soon as the killed process is reaped, so `FilesChanged` normally lands
  first. Even in the reverse order, `refreshProjectFiles()` does not depend on the session owner, so
  pending changes are still reflected.

**Why it is still a defect (fragility).** The correctness of a user-initiated stop depends on the
literal string `"Stopped by user"` continuing to be excluded by *two* unrelated substring filters in
the ViewModel. Any change to either the message text or the filters — e.g. wording like
`"API key rejected — stopped by user"` — would make a deliberate stop trigger an unwanted key
rotation plus an automatic session restart (`retryWithNextApiKey` re-launches the session). A stop
should be a first-class terminal state (e.g. `RuntimeEvent.SessionStopped`), or `SessionState` should
carry the stop flag into the event so the ViewModel does not have to pattern-match on prose.

---

## B. Flagged bug #2 — `sessionHome` deletion uses host path while `$DSH_HOME` is a guest path

**File:** `app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:197, 201, 281, 525-526, 551-554`

The suspicion: line 281 deletes the **host** `File` returned by `writeDshSettings`, while the guest
sees `$DSH_HOME` as the **guest** path from `dshHomeGuestPath()` — so the delete might hit the wrong
location and leave the per-session home behind as garbage.

**Verdict: NOT A BUG — host and guest paths coincide.** Evidence:

1. PRoot is launched with the rootfs directory as the guest root:
   `RuntimeInstaller.kt:1285-1286` → `add("-r"); add(rootfs.absolutePath)`.
2. The only bind mounts are `/dev`, `/proc`, `/sys`, `/system`, `/apex`, `/vendor`, `/product`, the
   workspace, and the bridge (`RuntimeInstaller.kt:1287-1305`). **`/root` is not bind-mounted**, so
   guest `/root` is the real directory `<rootfs>/root`.
3. `ensureRootfsCompatibilityLinks` (`RuntimeInstaller.kt:1188-1216`) creates only `bin`, `lib`,
   `sbin` symlinks. It never touches `root`, so `/root` is not a symlink either.

Therefore `<rootfs>/root/.dsh-<suffix>` (the host `File` deleted at line 281) and
`/root/.dsh-<suffix>` (the guest `$DSH_HOME`) are the same directory. The cleanup is correct.

**Minor residual note (Low):** the two representations of the same path are derived in two different
places (`writeDshSettings` builds the host form, `dshHomeGuestPath` the guest form) with no shared
helper asserting their equivalence. If a future rootfs layout ever makes `/root` a symlink or a bind
mount, this silently breaks and leaks one directory per session. Worth a comment or a single
source of truth.

---

## C. Runtime layer — additional defects found by direct read-through

### C1. UTF-8 multibyte characters split across output chunks silently corrupt protocol lines — Medium

**File:** `DshRuntimeBridge.kt:414-428`

```kotlin
val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file -> ... file.read(bytes) }
...
pendingOutput.append(bytes.decodeToString(0, count))
```

`outputOffset` counts **bytes**, but each chunk is decoded to a String independently. `decodeToString`
replaces malformed input with U+FFFD, so when a 16 KB read boundary lands in the middle of a
multibyte UTF-8 sequence, the trailing partial bytes are consumed (offset advances) and destroyed.
The affected line then fails `JSONObject(line)` in `parseLine` (`DshRuntimeBridge.kt:897`) and is
returned as `Ignored` — the whole line is dropped.

**Consequence:** any non-ASCII content — an emoji or accented character in the assistant's reply, a
Unicode filename in a `tool/result` — can cause that entire JSON-RPC line to be silently discarded
the moment a read splits it. The user sees a missing tool result or a gap in the reply text with no
error. The protocol is newline-framed, so the fix is to buffer undecoded bytes and only decode up to
the last newline (or decode incrementally and retain the partial tail).

### C2. `buildContextPrompt` history filters can silently drop legitimate user messages — Low

**File:** `DshRuntimeBridge.kt:637-644`

```kotlin
val priorMessages = history
    .filter { msg ->
        (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
        !msg.text.startsWith("Failed to") &&
        !msg.text.startsWith("Error:") &&
        !msg.text.contains("API Error")
    }
    .dropLast(1)
```

The `.dropLast(1)` is **correct** — verified against the caller: `MainViewModel.kt:3381` builds
`history = state.value.messages` *after* the just-sent message is in state, and the same message is
re-sent as `currentPrompt` (`DshRuntimeBridge.kt:700-701`). So dropping the last entry avoids
duplication. Not a bug.

The filters are the soft spot: a user message that happens to begin with `"Failed to"` or
`"Error:"`, or that contains `"API Error"`, is dropped from the conversation replay with no
indication. The agent then loses that turn's context. Low severity because these are prose
heuristics, but they should be scoped to assistant/system messages (the intent is clearly to strip
generated error banners and the demo prompt), not applied to `msg.fromUser` text.

### C3. Dead and misleading constant `DSH_HOME_GUEST_PATH` — Low

**File:** `DshRuntimeBridge.kt:780`

`const val DSH_HOME_GUEST_PATH = "/root/.dsh"` is declared but grep shows **no reference anywhere**.
The real per-session home is built by `dshHomeGuestPath()` at line 551-553 as `"/root/.dsh-$suffix"`.
The constant is not only dead code, it is actively misleading — it suggests the guest home is a
fixed `/root/.dsh`, which would collide across concurrent sessions (exactly the race the
`-<suffix>` scheme was introduced to fix).

### C4. `friendlyError` can misclassify messages containing "401"/"403"/"429" — Low

**File:** `DshRuntimeBridge.kt:624-625`

```kotlin
listOf("401", "403", "429").any { code ->
    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
}
```

A message like `"HTTP fetch failed for port 4010"` or `"error at line 401 of build.gradle"` contains
both a status-code substring and "HTTP", and is rewritten to `"The provider rejected the saved API
key."` — a misleading diagnosis. Combined with `retryWithNextApiKey` this can trigger an unnecessary
key rotation and an automatic session restart. Low probability, but the consequence is a real
user-visible false action.

### C5. Final flush after process death can flip a completed session to failed — Low/Medium

**File:** `DshRuntimeBridge.kt:430-432`

```kotlin
pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let {
    handle(parser.parseLine(it))
}
```

If the harness's final `session.status { running: false }` line is written **without a trailing
newline**, it stays in `pendingOutput`; the while loop at line 400 then exits once the process dies,
and the line is parsed here. `handle()` for `Status(running=false)` executes
`send("shutdown", SDK_SHUTDOWN_ID)` on a writer whose process is already dead. `send()` does not
catch I/O failures (`DshRuntimeBridge.kt:310-319`), so the `IOException` propagates out of
`runSdkSession`, into `startSession`'s `onFailure`, and the session is reported as **failed** even
though the harness completed normally. The `Status` handler should no-op once the process is dead
(the loop-exit condition should set a flag that `handle` checks), or `send()` should tolerate a dead
pipe.

### C6. GitHub PAT written to disk in plaintext inside the rootfs — Security (accepted-by-design, noted)

**File:** `DshRuntimeBridge.kt:70-126`, used at `98`

The PAT is materialized as `<rootfs>/root/.secrets/github-token` in cleartext so the running agent
and `git` credential helper can read it immediately (documented at lines 61-68 as an intentional
trade-off, since an env var wouldn't reach an already-running session). The file lives under the
app's private storage so it is not exposed to other apps, and teardown removes it when the token is
cleared. Recorded as a security note rather than a defect — but the file inherits the default umask,
so any process inside the single-user PRoot guest can read it; if a second tenant is ever introduced
into the guest, this needs a permission review.

### C7. `updateGithubToken` writes can interleave and leave a stale token — Low

**File:** `DshRuntimeBridge.kt:54-59, 131`

`updateGithubToken` launches on a standalone `credentialScope` with no ordering guarantee. Two rapid
updates (e.g. user pastes a token, then clears it) can execute out of order and leave the cleared
token on disk. The next `startSession` re-applies the current value synchronously (line 188), so it
self-heals, but an in-flight session could read a token the user believed was removed.

---

## D. Foreground service layer (`RuntimeExecutionService.kt`)

### D1. Foreground service type — manifest correctly configured, one open question — Low

**Files:** `AndroidManifest.xml:33-48`, `RuntimeExecutionService.kt:95-98`

I checked the manifest, and the `specialUse` setup is done correctly:
- `foregroundServiceType="specialUse"` is declared for **both** services (lines 36, 44),
- the required `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property is present with a real
  description (lines 37-39, 45-47), and
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` is requested (`AndroidManifest.xml:7`).

So the manifest half of the Android 14 requirement is satisfied — no defect there.

The open question is the code half: `RuntimeExecutionService.kt:95` calls the two-argument
`startForeground(RUNNING_NOTIFICATION_ID, notification)`, which relies on the manifest-declared type
rather than passing an explicit `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` bitmask. This is
the common pattern and the manifest declaration is what the platform reads, so it should be fine —
but I could not verify the exact API 34+ behaviour for `specialUse` from the official docs (the page
I fetched came back localized and truncated), so I am flagging it as **verify on an API 34+ device**
rather than asserting it is broken. If the system rejects it, the symptom is the long-running coding
task being killed in the background, which is exactly what this service exists to prevent.

*(The failed UI-layer auditor would also have covered the notification/notification-channel paths; it
is being re-dispatched.)*

---

## E. Orchestration & checkpoint layer

### E1. `Orchestrator` swallows `CancellationException`, breaking structured concurrency — Medium

**File:** `app/src/main/java/com/jarves/mh/agent/Orchestrator.kt:109` and `:140`

Both retry blocks catch bare `Exception` around a suspending call:

```kotlin
try {
    while (attempt <= config.maxTaskRetries) {
        try {
            output = executor.execute(st.instruction)   // suspending
            ...
        } catch (e: Exception) {                          // line 109 — also catches CancellationException
            lastError = e
            if (attempt < config.maxTaskRetries) {
                delay(backoffMs)                          // suspending, re-raises on cancel
            }
            attempt++
        }
    }
    ...
} catch (e: Exception) {                                  // line 140 — also catches CancellationException
    ShardResult(... succeeded = false, error = e.message ...)
}
```

**Mechanism.** When the workflow's coroutine is cancelled (user stops the run, the screen is left,
or the parent scope is torn down), `executor.execute()` throws `CancellationException`. The inner
catch treats it as an ordinary failure and, if retries remain, calls `delay()` — which is itself
cancellable and immediately re-raises `CancellationException`. That escapes the inner catch and is
finally swallowed by the outer catch at line 140.

**Consequence.** A user-initiated stop is reported as `ShardResult(succeeded = false,
error = <cancellation message>)`, surfacing to the user as a task **escalation/failure** rather than
a clean cancellation. This is the same class of defect as flagged bug #1 (a stop masquerading as a
failure), one layer up. It also violates structured concurrency: the coroutine keeps running its own
teardown instead of unwinding promptly.

**Fix.** Rethrow before the generic catch, at both sites:
```kotlin
} catch (e: CancellationException) { throw e }
} catch (e: Exception) { ... }
```

### E2. `safeWorkspaceFile` validates the file's *parent*, not the file — latent traversal escape — Medium (latent)

**File:** `app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt:216-223`

```kotlin
fun safeWorkspaceFile(root: File, relative: String): File {
    require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe workspace path" }
    val file = File(root, relative)
    val rootPath = root.canonicalFile.toPath()
    val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
    require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
    return file
}
```

The guard canonicalises and bounds-checks `file.parentFile`, **not `file` itself**. For an input such
as `"a/../.."`:

- `file` = `<root>/a/../..`, whose canonical path is `<root>`'s **parent** — i.e. outside the
  workspace;
- but `file.parentFile` = `<root>/a/..`, which canonicalises back to `<root>`, so `startsWith`
  passes and the function returns the escaping `File`.

So the "safe" contract is violated by any relative path that ends in a net upward traversal. The
`startsWith` check itself is sound — `Path.startsWith` compares by path *elements*, so a sibling
`<root>foobar` is correctly rejected.

**Reachability today — low, hence "latent".** Every caller-supplied `path` is internally derived:
`undoLastChanges` (`DshRuntimeBridge.kt:465`) and `loadPendingChanges` (`:489`) read paths from
`readChangedPaths`, and `undoFileChange`/`acceptFileChange` (`:494,510`) require `path` to already be
in that set. Those paths come from `changedFiles()`/`snapshot()` (`WorkspaceCheckpoints.kt:225-231`),
which use `walkTopDown().relativeTo(root)` — a walk that never yields `..`. An agent therefore cannot
inject a traversal string through the normal flow.

**Why it still matters.** The function's name promises containment it does not deliver for arbitrary
input. Any future caller that passes a less-sanitised path — agent tool output, a deep link, a JSON
manifest — gets a silent escape, and this is exactly the boundary that handles *undo/restore writes*
(copyTo/overwrite/delete at `DshRuntimeBridge.kt:470-474`). The fix is one line: bound-check the
file, not its parent.

```kotlin
val filePath = file.canonicalFile.toPath()
require(filePath.startsWith(rootPath)) { "Workspace path escapes project" }
return file.canonicalFile   // return the resolved form
```

### E3. SharedFlow configuration is a backpressure stall waiting to happen — Medium

**Files:** `DshRuntimeBridge.kt:133` (`extraBufferCapacity = 64`), `MessageBus.kt:8` (100),
`AgentSystem.kt:28` (100), `HeartbeatMonitor.kt:12` (50); collected at `MainViewModel.kt:426`

The auditor that first raised this called it a "SharedFlow deadlock." That overstates it, but it
pointed at a real configuration problem. Verified facts:

1. Every flow is `MutableSharedFlow(extraBufferCapacity = N)` with **default `replay = 0`** and
   **default `onBufferOverflow = BufferOverflow.SUSPEND`**.
2. Every emission uses the suspending `.emit()` — there are **zero** `tryEmit()` call sites in the
   app. So a full buffer suspends the emitter rather than dropping the event.
3. `DshRuntimeBridge` emits `AssistantDelta` for *every token chunk* of a reply (`:391`), plus
   `ToolCall`/`ToolResult`/`FilesChanged` events, all from the `Dispatchers.IO` session reader loop.
4. The sole runtime-event collector, `MainViewModel.kt:426`, runs on `viewModelScope` — i.e.
   `Dispatchers.Main.immediate` — and processes events **serially** through `onRuntimeEvent`.

Because emitter and collector are on different dispatchers, this is **not** a self-deadlock. The real
hazard is backpressure: if the Main-thread collector cannot keep up (a `refreshProjectFiles()` on
`FilesChanged`, a recomposition storm, or the app being backgrounded and the UI thread throttled),
the 64-slot buffer fills, and `emit()` **suspends the session reader loop** — the agent's stdout is
no longer drained and the whole run stalls until the UI catches up. The effect is a pause rather than
a hang, but it couples agent throughput to UI smoothness, and on a low-end device a single slow
`FilesChanged` can visibly stall a generation.

Secondary note on `replay = 0`: events emitted before a subscriber attaches are permanently lost.
`MainViewModel` attaches its collector in `init` before any session can start, so nothing is missed
in practice — but the margin is thin. If the collection is ever moved or lazily attached,
`SessionStarted` (the event that lets the UI map a session id to its owner) would be dropped
silently.

**Fix options (choose per flow):** for the high-volume `eventBus`, use
`BufferOverflow.DROP_OLDEST` with a small `replay` (token deltas are loss-tolerant by design — the
UI re-renders from the accumulated message state), or move expensive `onRuntimeEvent` work off the
Main thread. Keep `SUSPEND` only for flows where losing an event is worse than stalling.

---

## F. Coverage and method notes

- **Audited by direct read-through:** `DshRuntimeBridge.kt`, `RuntimeInstaller.kt` (path/proot
  layer), `WorkspaceCheckpoints.kt`, `Orchestrator.kt`, `MessageBus.kt`, `AgentSystem.kt`,
  `HeartbeatMonitor.kt`, `RuntimeExecutionService.kt`, `AndroidManifest.xml`, and the event
  consumers in `MainViewModel.kt`.
- **Partially covered:** the UI/ViewModel layer (`MainViewModel.kt`, 4245 lines) was audited only
  along the event-handling and session-lifecycle paths reachable from the findings above. A
  dedicated pass over file editing, project import/export, and the in-app updater was attempted via
  subagent but that run failed and was not re-run.
- **Not verified locally:** the API 34+ behaviour of the two-argument `startForeground` for a
  `specialUse` foreground service (see D1) — the Android docs page I fetched came back localised and
  truncated, and there is no JVM/Android toolchain in this environment to test against. Flagged as
  "verify on an API 34+ device," not as a defect.
- **Not a bug (cleared):** flagged bug #2 (the `sessionHome` host-vs-guest path) — see section B.
  Host `<rootfs>/root/.dsh-<suffix>` and guest `/root/.dsh-<suffix>` are the same directory because
  PRoot uses the rootfs as guest `/` and `/root` is neither bind-mounted nor symlinked.

---

## G. Resolution log

Applied in the follow-up commit to this audit. The two flagged issues resolved as follows:
**#1** was a real defect and is now fixed via a first-class `RuntimeEvent.SessionStopped`;
**#2** was confirmed *not* a bug (host and guest paths coincide under PRoot) and was left as-is
with the residual note recorded in section B.

| ID | Severity | Resolution |
|---|---|---|
| A (#1) | Defect | **Fixed.** New `RuntimeEvent.SessionStopped`; both stop paths route through `emitStoppedOnce`. `emitFailureOnce` now handles genuine failures only. The ViewModel has a dedicated `SessionStopped` branch, so a stop is terminal *by type* and can never reach `retryWithNextApiKey` regardless of wording. |
| B (#2) | Not a bug | **Cleared.** Left unchanged; note about the two path representations lacking a shared source of truth stands. |
| C1 | Medium | **Fixed.** The reader now buffers raw bytes and decodes only up to the last newline (`\n` cannot occur inside a UTF-8 multibyte sequence), so a read boundary can no longer split a character. |
| C2 | Low | **Fixed.** The error-banner filters in `buildContextPrompt` now apply only to non-user messages, so a user turn starting with "Failed to"/"Error:" is never dropped from the replay. |
| C3 | Low | **Fixed.** Removed the dead, misleading `DSH_HOME_GUEST_PATH` constant. |
| C4 | Low | **Fixed.** Status-code matching is now word-bounded (`\b(?:401|403|429)\b`), so "port 4010" or "line 401" can no longer be misread as an auth rejection. |
| C5 | Low/Medium | **Fixed.** The shutdown frame is only written while the process is alive, so a posthumous final flush cannot throw IOException and flip a completed session to failed. |
| C6 | Security note | **Accepted-by-design**, unchanged. Plaintext PAT under the app-private rootfs, documented in-code; revisited only if the guest gains a second tenant. |
| C7 | Low | **Fixed.** Credential updates now flow through a conflated channel drained by a single consumer, so writes apply in order and the most recent value always wins on disk. |
| D1 | Low | **Addressed.** `startForeground` now passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` explicitly via `ServiceCompat` (which falls back to the 2-arg call below API 29). The manifest half was already correct. |
| E1 | Medium | **Fixed.** Both `Orchestrator` retry blocks rethrow `CancellationException` before the generic catch, so a stop unwinds cleanly instead of being reported as a shard failure. |
| E2 | Latent | **Fixed.** `safeWorkspaceFile` now bounds-checks the normalised *file* path, not its parent. Uses lexical normalisation so legitimate in-project files and symlinked path prefixes are unaffected. Covered by `WorkspaceCheckpointsPathSafetyTest`. |
| E3 | Medium | **Mitigated.** The event bus buffer was raised to 512. `SUSPEND` overflow is kept deliberately: the bus carries terminal events and streaming deltas that the UI accumulates, so the audit's suggested `DROP_OLDEST` would have lost reply text. The residual coupling to Main-thread collector speed is noted below. |

### G.1 Known residuals (deliberate)

- **E3 (partial):** raising the buffer absorbs Main-thread pauses but does not remove the
  coupling. The durable fix is to move the collector off `Dispatchers.Main` or to split the
  lossy streaming deltas from the loss-intolerant lifecycle events. That requires making
  `sessionProjects` / `backgroundBuffers` / `failedApiKeyIds` concurrency-safe, and it was
  not done blind without a build to verify against.
- **D1 (partial):** the explicit type removes the ambiguity, but the API 34+ runtime
  behaviour still cannot be verified without a device — see section F.
- **UI/ViewModel layer:** still only audited along the paths reachable from these findings.
