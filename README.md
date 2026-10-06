<div align="center">

  <img src="assets/readme/icon.png" alt="Agentic" width="120" height="120" />

  # Agentic

  ### *AI-Powered Mobile Coding IDE with Multi-Agent Orchestration.*

  **Chat with coding agents, edit projects, execute real Linux commands, preview live web servers, and orchestrate multi-agent workflows — all directly on your phone.**

  <br />

  <br />

  [![Release v2.0.0](https://img.shields.io/badge/Release-v2.0.0-F28C52?style=flat-square&logo=github&logoColor=white)](https://github.com/elias-veyne/AGENTIC/releases)
  [![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#system-requirements)
  [![ARM64](https://img.shields.io/badge/CPU-ARM64-5B8DEF?style=flat-square)](#system-requirements)
  [![MIT License](https://img.shields.io/badge/License-MIT-8B7CF6?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)

  <br />

  [**Quickstart Guide**](#quickstart) &nbsp;•&nbsp;
  [**Architecture**](#architecture) &nbsp;•&nbsp;
  [**Build from Source**](#developer-guides)

</div>

<br />

---

> [!IMPORTANT]
> **Environment Security Notice**  
> Agentic runs on **ARM64 Android devices** using a private userspace PRoot layer. While isolated from other apps via standard Android sandbox permissions, PRoot is not a virtualization boundary or hardened security jail. Only execute projects and dependencies you own or trust.

<br />

## Download Agentic

<div align="center">
  <h3>One edition, always current</h3>
  <p>Agentic ships as a single online build. Core and Python runtime bundles are downloaded only when needed, and every release is signed and delivered through secure in-app updates.</p>
  <a href="https://github.com/elias-veyne/AGENTIC/releases/latest">
    <img src="https://img.shields.io/badge/Download-Latest_APK-F28C52?style=for-the-badge&logo=android&logoColor=white" alt="Download Agentic APK" />
  </a>
</div>

> [!NOTE]
> **On-device Android toolchain (optional).** The Android SDK, JDK, and Gradle add roughly 570 MB — install the Android stack in Settings → Development stacks when a project needs on-device `gradle` builds, or keep building via the GitHub Actions workflow and install the signed APK as an in-app update.

<p align="center">
  <strong>ARM64 Android 9+</strong><br />
  <sub>Direct APK installation · No root required · No USB or wireless ADB pairing</sub>
</p>

<br />

## Capabilities

Agentic unites modern **Jetpack Compose UI** with a self-contained **Ubuntu 20.04 LTS subsystem**. It gives you a desktop-class software development environment in your pocket without requiring root access, unlocked bootloaders, or external applications like Termux.

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>Single & Multi-Agent Modes</h3>
      <p>Toggle between direct DeepSeek Harness execution and multi-agent orchestration. The orchestrator decomposes tasks, manages worker health, and handles collaborative workflows.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Heartbeat Health Monitoring</h3>
      <p>Workers send periodic heartbeats (30s interval). The orchestrator automatically detects and recovers from unresponsive agents, ensuring resilient task execution.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>Agent Orchestrator</h3>
      <p>Central task decomposition and worker assignment engine. Subtasks are distributed, monitored, and results are aggregated with callback notifications.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Isolated Linux Subsystem</h3>
      <p>A full Ubuntu 20.04 ARM64 userspace running inside PRoot. Includes Node.js, npm, Git, OpenSSL, and essential shell tooling out of the box.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>Instant Web Preview</h3>
      <p>Spun up a Vite, Next.js, or Express server? Test web interfaces in real-time within a restricted, sandboxed mobile WebView with console telemetry.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Keystore-Grade Encryption</h3>
      <p>Your API keys and credentials are encrypted using Android Keystore-backed AES-256 GCM. No telemetry, no remote proxies, and zero plain-text leaks.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>Persistent Project Sessions</h3>
      <p>Keep projects, chat history, files, and task context together so work can continue across app sessions.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Native File Workflow</h3>
      <p>Browse, edit, search, and attach files directly from the app interface. Interoperate with system storage via Android Storage Access Framework (SAF).</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>CI Android Builds</h3>
      <p>Build and sign Android APKs through your repo's GitHub Actions. The agent edits on-device and installs the signed update straight from CI.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Optional Toolchains</h3>
      <p>Add Python, C/C++, and PHP tooling only when a project needs it — downloaded on demand, never bundled up front.</p>
    </td>
  </tr>
</table>

<br />

## Quickstart

Get up and running in 3 guided steps:

### 1. Download & Install
Download the latest signed release APK from [GitHub Releases](https://github.com/elias-veyne/AGENTIC/releases/latest).

```text
Target Architecture : ARM64 (arm64-v8a)
Minimum OS Level    : Android 9.0 (API 28)
```

### 2. Guided Bootstrap (~10 Minutes)
Launch the application and follow the interactive setup wizard:

1. **System Readiness** — verifies device storage, CPU architecture, and background service permissions.
2. **Toolchains** — select the core Ubuntu runtime and any optional development stacks.
3. **AI Provider** — securely store your API keys in Android Keystore.

### 3. Create & Build
1. Tap **New Project** or launch an instant **Quick Project**.
2. Open the **AI Workspace** and describe what you want to build.
3. Watch the agent inspect files, draft code, run builds, and launch local web previews.
4. **Multi-Agent Mode**: Switch in Settings to enable task decomposition and worker coordination.

<br />

## Agent Configuration

Agentic uses **DeepSeek Harness** as its agent engine, with a provider-agnostic routing layer that speaks each provider's native wire protocol. Every provider is available in every session, and each chat picks its own model:

| Provider | Protocol | Streaming | Tool Calling | Status | Notes |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **Anthropic** | Anthropic Messages | Supported | Supported | `Recommended` | Native `claude-sonnet-4-6` support |
| **DeepSeek** | Anthropic-compatible | Supported | Supported | `Recommended` | `deepseek-v4-flash` / `deepseek-v4-pro` |
| **OpenRouter** | OpenRouter Gateway | Supported | Supported | `Supported` | Routes compatible models through one API key |
| **Kimi** | Anthropic-compatible | Supported | Supported | `Supported` | Moonshot `kimi-k2.6` |
| **OpenCode Zen** | Per-model family | Supported | Supported | `Supported` | GPT, Claude, DeepSeek, Qwen, GLM, Gemini via one gateway |
| **NVIDIA NIM** | OpenAI-compatible | Supported | Supported | `Supported` | Self-hosted NIM endpoints |
| **Custom API** | Anthropic-compatible | Compatible | Compatible | `Experimental` | User-configured endpoint |

> [!NOTE]
> OpenCode Zen serves different model families on **different wire endpoints** — GPT models use `/responses`, Claude uses `/messages`, DeepSeek/Qwen/GLM/Kimi use `/chat/completions`, and Gemini uses `/models/<id>`. Agentic resolves the endpoint from the model family rather than assuming one protocol for the whole provider.

**Multiple models at once.** You are not limited to one provider per session. The model registry stores every model you connect as an independent entry — its own label, base URL, model id, and API key — so you can add your first DeepSeek model, then use **Add new model** to connect an OpenRouter or custom-endpoint model alongside it. Each chat picks its model from this list, and the home screen's **Total Agents** card shows how many models are connected while **Active Agents** shows how many are actively working.

> [!NOTE]
> API keys are stored with hardware-backed Android Keystore AES-256-GCM encryption, each scoped to its own model. Keys are sent directly to your chosen provider; no intermediate relays collect your prompts or code.

<br />

## Architecture

Agentic bridges native Android Jetpack Compose to an isolated PRoot Linux execution layer via an optimized C++ JNI bridge. The whole product is one Gradle module — these are the packages that do the work:

```text
app/src/main/
├── java/com/jarves/mh/
│   ├── agent/                   # ← Multi-agent orchestration engine
│   │   ├── AgentSystem.kt       #   System entry point & mode (single vs multi)
│   │   ├── Orchestrator.kt      #   Task decomposition, worker assignment, aggregation
│   │   ├── Decomposer.kt        #   Splits a task into concurrent shards
│   │   ├── HeartbeatMonitor.kt  #   30s worker liveness & automatic recovery
│   │   ├── MessageBus.kt        #   Inter-worker message dispatch
│   │   ├── SharedStateStore.kt  #   Cross-worker scratch state
│   │   └── CooperativeSession.kt#   Peer-to-peer agent sessions (agent2/agent3)
│   ├── data/                    # Encrypted persistence layer
│   │   ├── ApiKeyVault.kt       #   Android Keystore AES-256-GCM credential vault
│   │   ├── AppPreferences.kt    #   DataStore-backed settings & per-chat model bindings
│   │   └── ModelRegistry.kt     #   Connected models: label, base URL, model id, key
│   ├── model/
│   │   └── Models.kt            #   Provider/model entities, Zen per-family protocol map
│   ├── network/
│   │   ├── GitHubClient.kt      #   Device-flow OAuth, repo & PR operations
│   │   └── ProviderApiClient.kt #   Provider-side model discovery
│   ├── integrations/
│   │   └── GitHubService.kt     #   Encrypted PAT storage & GitHub REST calls
│   ├── update/
│   │   └── AppUpdater.kt        #   Signed in-app update download & install
│   ├── runtime/                 # ← The Linux subsystem + agent engine
│   │   ├── DshRuntimeBridge.kt  #   DeepSeek Harness JSON-RPC, per-session DSH_HOME & state
│   │   ├── RuntimeInstaller.kt  #   PRoot rootfs provisioning, bundle download & checksums
│   │   ├── RuntimeBridge.kt     #   Shared process/workspace surface
│   │   ├── NativeSpawnProcess.kt#   C++ JNI process launcher & pipe multiplexer
│   │   ├── AgentDriver.kt       #   Per-agent install drivers (DeepSeek SDK profile)
│   │   ├── RuntimeExecutionService.kt   # Foreground service: lifecycle & wakelocks
│   │   ├── RuntimeSetupService.kt       # First-run bootstrap & bundle staging
│   │   ├── WorkspaceCheckpoints.kt      # Project snapshots with undo
│   │   ├── AndroidAppInstaller.kt       # APK build-output install via package installer
│   │   └── LocalFormatGateway.kt        # Live web preview gateway
│   └── ui/                      # Jetpack Compose (Material 3)
│       ├── PocketDevApp.kt      #   Nav host & download/toolchain surface
│       ├── MainViewModel.kt     #   Session lifecycle, concurrent shards, model resolution
│       ├── AgentScreen.kt       #   Chat + tool-call rendering
│       ├── TerminalScreen.kt    #   Terminal over the process bridge
│       ├── GitHubScreen.kt      #   OAuth & repo browser
│       ├── SettingsScreen.kt    #   Providers, PAT credential pipeline, stacks
│       ├── ModelManagerSheet.kt #   Add/switch models per chat
│       ├── chat/                #   Chat mode content, smooth follow-scroll
│       ├── onboarding/          #   Setup wizard
│       ├── orbs/                #   Live wallpaper pet engine
│       └── theme/               #   Glassmorphism theme
├── cpp/                         # ← Native layer (CMake, NDK)
│   ├── pocket_launcher.c        #   Rootfs pivot & PRoot entry
│   ├── pocket_spawn.c           #   PTY-style process spawn & I/O multiplexing
│   └── executable_carrier.c     #   ELF carrier so AGP packages the launcher .so
├── assets/                      #   Rootfs checksums, licenses, base config
└── res/                         #   Adaptive icons, notification glyph, drawables
```

**Request path, end to end:** a chat resolves its bound `(provider, model, key)` from `ModelRegistry` → `MainViewModel` injects the key per-process as an env var and writes a **per-session** `DSH_HOME` so concurrent sessions never share one `settings.yaml` → `DshRuntimeBridge` speaks newline-delimited JSON-RPC to `dsh` over the C++ pipe bridge → the orchestrator fans concurrent shards out to workers that heartbeat every 30s → tool output streams back and is rendered in Compose.

### Core Runtime Components
* **Base Environment**: Ubuntu 20.04 ARM64 verified rootfs
* **Agent Engine**: DeepSeek Harness (DSH) CLI integration with provider-agnostic API routing
* **Multi-Agent Orchestration**: Orchestrator with worker monitoring (30s heartbeat), task callbacks, and automatic recovery
* **Native Tooling**: Node.js LTS, npm, Git, OpenSSL, curl, and GNU coreutils
* **Process Virtualization**: PRoot user-space architecture emulation with zero kernel modifications

<br />

## System Requirements

| Metric | Minimum Specification | Recommended Specification |
| :--- | :--- | :--- |
| **Operating System** | Android 9.0 (API level 28) | Android 13.0+ (API level 33+) |
| **CPU Architecture** | 64-bit ARM (`arm64-v8a`) | High-performance 8-Core ARM64 (Snapdragon 8 Gen 1+ / Dimensity) |
| **RAM** | 4 GB | 8 GB or more |
| **Free Storage** | 2.5 GB (Base Runtime) | 8.0 GB+ (For multi-language toolchains and build caches) |
| **Network** | Stable connection for setup & API | High-speed Wi-Fi during initial rootfs provisioning |

<br />

---

## Developer Guides

<details>
<summary><b>Building from source (Android Studio & NDK)</b></summary>

<br />

### Prerequisites
* **Android Studio**: Ladybug / Hedgehog or newer
* **Android SDK**: API Level 36 (`compileSdk 36`)
* **Java Development Kit**: JDK 17 (Eclipse Temurin or OpenJDK)
* **Android NDK**: `26.1.10909125`
* **CMake**: `3.22.1`

### Clone & Build Debug APK
```bash
# Clone the repository
git clone https://github.com/elias-veyne/AGENTIC.git
cd AGENTIC

# Build the standard ARM64 debug binary
./gradlew assembleDebug

# Deploy directly to a connected test device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Quality Assurance & Testing
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run static analysis linter
./gradlew lintDebug
```

### Target Profiles
* **Direct Sideload APK** (Default): Targets API 28 to preserve proven userspace execution paths under Android 10-14.
* **Google Play Compliance Build**:
  ```bash
  ./gradlew -PplayBuild=true assembleDebug
  ```
  Refer to the [Google Play Release Checklist](docs/PLAY_STORE_CHECKLIST.md) for signing and permission policies.

</details>

<details>
<summary><b>Optional toolchains and development stacks</b></summary>

<br />

Agentic allows downloading optional developer packs on demand to conserve space:

* **Python Suite**: Python 3.10+, pip, virtualenv, and essential scientific C-extensions.
* **C / C++ Compiler Suite**: GCC/G++, Clang, Make, and CMake for native tool compilation.
* **PHP Development**: PHP CLI runtime, Composer, and standard database extensions.

> **Android builds, two ways.** Add the Android development stack in Settings → Development stacks for on-device `gradle` builds (JDK 17, Android SDK 36, Build Tools 35, Gradle 8.14.3, offline Maven cache — ~570 MB downloaded on demand, or bundled when compiling the `offline` flavor via `./gradlew assembleOfflineRelease`). Or keep the default: the agent edits here and this repo's GitHub Actions workflow builds and signs the APK on CI, installed as an in-app update.

> *Note: Kernel-level virtualization technologies such as Docker, KVM, systemd services, and nested hardware emulators are not supported under PRoot.*

</details>

<details>
<summary><b>Security model and data privacy</b></summary>

<br />

* **Zero Cloud Intermediaries**: Agentic connects your device directly to your chosen AI endpoint. No intermediate relays or telemetry servers collect your prompts or code.
* **Scoped Storage**: Project imports and exports utilize Android's official Storage Access Framework (SAF) instead of broad shared storage access.
* **Cryptographic Checksums**: Root filesystem archives and CLI packages are verified via SHA-256 checksums prior to extraction.
* **Encrypted Secrets**: API tokens are encrypted in hardware-backed storage via Android Keystore.

- ARM64 phones only
- No hardened isolation for hostile code
- Terminal input/output currently uses a process bridge rather than a complete PTY emulator; full-screen interactive programs may render incorrectly
- Background tasks are subject to Android process and battery policies
- Custom providers may lack Claude-compatible thinking, tool use, token counting, or streaming behavior
- Large builds can be slow and memory-intensive under PRoot
- Runtime installation requires a substantial download and free storage
- Project-specific Android libraries may still be downloaded by Gradle when they are not already in the bundled Maven cache

</details>

Agentic is currently intended for signed direct APK distribution and private testing. Its Android-project workflow requests permission to submit user-built APKs to Android's package installer, which requires a dedicated Google Play policy declaration and approval if distributed through Play.

<br />

## Current Limitations

* **Architecture**: Exclusively supports 64-bit ARM (`arm64-v8a`) hardware.
* **Process Isolation**: PRoot maps file systems and IDs in user space; it is not a cryptographically hardened container or VM.
* **Terminal Emulation**: The process bridge handles standard CLI workflows and REPLs; specialized ncurses applications may experience minor layout artifacts.
* **OS Process Management**: Heavy compilation workloads may be throttled if Android applies aggressive battery optimization. It is recommended to exempt Agentic from battery optimization in device settings.

<br />

## Legal & Trademarks

* Agentic is an independent open-source project and is not affiliated with, endorsed by, or sponsored by DeepSeek or any model provider.
* Ubuntu, Android, Kotlin, Node.js, Git, and other registered trademarks belong to their respective copyright holders.
* Third-party open-source licenses are compiled in [`app/src/main/assets/licenses`](app/src/main/assets/licenses).

<br />

## License

This project is licensed under the [MIT License](LICENSE). Third-party runtime binaries and packages remain governed by their respective upstream licenses.

<br />

---

<div align="center">
  <sub>Crafted for developers who want a serious, uncompromised development environment wherever they go.</sub>
  <br />
  <sub>Copyright © 2026 AGENTIC. All rights reserved.</sub>
</div>
