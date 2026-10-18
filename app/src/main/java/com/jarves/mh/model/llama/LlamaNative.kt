package com.jarves.mh.model.llama

/**
 * Raw JNI surface for the vendored llama.cpp runtime.
 *
 * ## Why loadLibrary is guarded rather than done in an init block
 *
 * The existing [com.jarves.mh.runtime.NativeSpawnProcess] calls `System.loadLibrary`
 * from an `object`'s initialiser, which is correct for code that is always present.
 * It is wrong here. `System.loadLibrary` throws [UnsatisfiedLinkError] when the
 * library is missing, and that happens legitimately on three paths:
 *
 *  - the `offline` flavour, or any build made before this library existed,
 *  - a device where the NDK produced a different ABI set,
 *  - unit tests, which run on a desktop JVM with no `libagentic-llama.so`.
 *
 * An error thrown during class initialisation cannot be caught at the call site, and
 * an uncaught [Error] on the main thread takes the process down. So loading is done
 * here into a flag, and [isLibraryLoaded] is what callers branch on. The `external`
 * declarations are still present because they are resolved lazily at first call, not
 * at class load.
 */
internal object LlamaNative {

    /**
     * Whether `libagentic-llama.so` could be loaded.
     *
     * `runCatching` catches [Throwable], which is required rather than merely broad:
     * [UnsatisfiedLinkError] is an [Error], so a `catch (e: Exception)` would not
     * catch it and the crash would survive.
     */
    val isLibraryLoaded: Boolean =
        runCatching { System.loadLibrary("agentic-llama") }.isSuccess

    external fun nativeBackendInfo(): String

    /** @return an opaque handle, or 0 on failure (a Java exception is thrown instead). */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeModelDescription(handle: Long): String

    external fun nativeContextSize(handle: Long): Int

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topK: Int,
        topP: Float,
    ): String?

    /**
     * Run a generation and report timings.
     *
     * @return `[ttftMs, totalDecodeMs, tokensGenerated, tokensPerSecond, promptTokens]`,
     *   where `ttftMs` is -1 if no token was produced.
     */
    external fun nativeBenchmark(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topK: Int,
        topP: Float,
    ): FloatArray
}
