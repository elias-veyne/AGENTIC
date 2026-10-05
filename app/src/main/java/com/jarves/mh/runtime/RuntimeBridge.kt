package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import kotlinx.coroutines.flow.Flow

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>

    /**
     * Runs one agent session to completion and returns its id.
     *
     * This does NOT return as soon as the session begins — it awaits the whole run. A
     * caller that needs to correlate the returned id with the events emitted *during*
     * that run must therefore pass [sessionId] itself; otherwise the id only becomes
     * available after the last event, and every correlation against it silently fails.
     *
     * @param sessionId caller-chosen id to use instead of a fresh UUID. Supply this when
     *   the caller must match `RuntimeEvent`s to this session while it is still running.
     */
    suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile, resolvedSecret: String? = null, sessionId: String? = null): String

    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

