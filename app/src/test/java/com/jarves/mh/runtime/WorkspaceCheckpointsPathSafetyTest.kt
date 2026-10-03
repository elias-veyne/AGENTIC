package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression guard for the containment contract of [WorkspaceCheckpoints.safeWorkspaceFile].
 *
 * The original implementation bounds-checked only the resolved *parent* directory. A
 * relative path ending in a net upward traversal — "a/../.." — has a parent that
 * canonicalises back inside the project while the file itself resolves outside it, so
 * the "safe" function happily handed back an escaping path. That is the boundary the
 * undo/restore flow writes through (copyTo / overwrite / delete), so it now checks the
 * file itself.
 */
class WorkspaceCheckpointsPathSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val checkpoints = WorkspaceCheckpoints(File("/nonexistent-test-root"))

    @Test
    fun ordinaryRelativePathsAreAccepted() {
        val root = tempFolder.newFolder("proj")
        val file = checkpoints.safeWorkspaceFile(root, "src/main/Foo.kt")
        assertEquals(File(root, "src/main/Foo.kt"), file)
    }

    @Test
    fun harmlessTraversalThatStaysInsideIsAccepted() {
        val root = tempFolder.newFolder("proj")
        // "a/../b" resolves to <root>/b, which is still inside the project.
        val file = checkpoints.safeWorkspaceFile(root, "a/../b")
        assertEquals(File(root, "a/../b"), file)
    }

    @Test
    fun netUpwardTraversalIsRejected() {
        val root = tempFolder.newFolder("proj")
        // The headline escape: parent canonicalises to <root>, the file to its parent.
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "a/../..")
        }
    }

    @Test
    fun escapingTraversalToSiblingFileIsRejected() {
        val root = tempFolder.newFolder("proj")
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "a/../../b")
        }
    }

    @Test
    fun bareParentTraversalIsRejected() {
        val root = tempFolder.newFolder("proj")
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "..")
        }
    }

    @Test
    fun siblingDirectoryWithSharedPrefixIsRejected() {
        // Path.startsWith compares by path element, so a sibling whose name merely
        // extends the project's ("proj-secret" vs "proj") must not pass as inside.
        val root = tempFolder.newFolder("proj")
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "../proj-secret/file.txt")
        }
    }

    @Test
    fun absoluteAndBlankPathsAreRejected() {
        val root = tempFolder.newFolder("proj")
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "/etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "")
        }
    }
}
