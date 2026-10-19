package com.jarves.mh.model

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * [StorageRoots] over an Android [Context].
 *
 * The only production implementation of the interface; everything else in `model/`
 * takes it as a constructor parameter so it can be faked in tests.
 *
 * ## Why index 0 is treated as "the primary volume" and may be null
 *
 * `Context.getExternalFilesDirs()` returns an array whose index 0 is the primary
 * emulated volume and whose later entries are adopted SD cards. Index 0 is **null**
 * when external storage is unavailable - emulated storage removed, or a device that
 * never had it - which is exactly the condition [ModelPathResolver] falls back to
 * internal storage for. Modelling that null rather than substituting a path keeps the
 * fallback decision in one place.
 */
class AndroidStorageRoots(context: Context) : StorageRoots {

    private val app = context.applicationContext

    override val externalFilesDir: File?
        get() = app.getExternalFilesDirs(null).firstOrNull()

    override val internalFilesDir: File
        get() = app.filesDir

    override val cacheDir: File
        get() = app.cacheDir

    /**
     * Adopted or removable volumes: `getExternalFilesDirs()` past index 0.
     *
     * Entries may be null for volumes that are present-but-unmounted; those are
     * filtered rather than passed through, since a null in this list would be
     * dereferenced by the caller's free-space walk.
     */
    override val secondaryExternalFilesDirs: List<File>
        get() = app.getExternalFilesDirs(null).drop(1).filterNotNull()

    /** Human-readable roots, for a diagnostics line. */
    fun describe(): String = buildString {
        append("primary=").append(externalFilesDir?.absolutePath ?: "unavailable")
        append(" internal=").append(internalFilesDir.absolutePath)
        val secondaries = secondaryExternalFilesDirs
        append(" secondary=").append(if (secondaries.isEmpty()) "none" else secondaries.joinToString { it.absolutePath })
    }

    /**
     * Device memory currently available, for the load projection.
     *
     * This must come from [ActivityManager], not from `Runtime.maxMemory()`. The
     * latter is the JVM *heap* ceiling - on Android typically 128-512 MB even on a
     * phone with 8 GB of RAM - while llama.cpp allocates almost entirely in native
     * memory that the heap figure does not describe.
     *
     * Using the heap number makes [com.jarves.mh.model.MemoryPlanner] refuse every
     * model on every device: a 639 MB 1B checkpoint projects to ~1.0 GB and needs
     * ~1.2 GB, which never fits inside a 256 MB heap ceiling.
     *
     * `availMem` is system-wide rather than this process's own budget, so it
     * over-reports what is truly available. That is the safe direction: the planner
     * would rather admit a load that then fails than refuse one that would have
     * fitted.
     */
    fun availableMemoryBytes(): Long {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem
    }

    companion object {
        /**
         * Directory a model must be pushed to when testing on a device by hand.
         *
         * Uses `applicationId` (`com.veyne.agentic`), **not** the Kotlin namespace
         * (`com.jarves.mh`). Android scopes external app storage by applicationId, so
         * using the namespace would name a path that cannot exist.
         */
        fun manualPushHint(): String =
            "/storage/emulated/0/Android/data/com.veyne.agentic/files/models/"
    }
}
