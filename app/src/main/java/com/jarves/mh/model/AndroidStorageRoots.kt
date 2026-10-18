package com.jarves.mh.model

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
