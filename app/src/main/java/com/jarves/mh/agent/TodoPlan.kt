package com.jarves.mh.agent

/**
 * Mod 4 — the agent-generated to-do list that drives a long-running task.
 *
 * The loop this models belongs to is deliberately not the existing "decompose
 * into parallel shards" path. That path fans out and merges; this one is
 * *sequential and persistent*: the agent first produces an ordered checklist,
 * the harness then keeps re-entering the model on the next un-finished item
 * until every item is done. That distinction is what makes a task "long-term"
 * rather than merely "parallel" — progress survives between turns and the loop
 * only ends when nothing is left outstanding.
 *
 * Instances are immutable snapshots; the harness owns the mutable list and
 * publishes a new snapshot to the UI on every status change, which is what
 * lets the checklist render a fresh checkmark the moment an item completes.
 */
data class TodoItem(
    /** Stable id so the UI can key rows and animate per-item transitions. */
    val id: String,
    /** The step, phrased as an action ("Add the login screen layout"). */
    val title: String,
    /** Optional clarification the model supplied for why the step is needed. */
    val detail: String? = null,
    val status: TodoStatus = TodoStatus.Pending,
    /**
     * Free-text note the agent may attach when an item finishes — typically the
     * path it touched or the reason it considers the step done.
     */
    val note: String? = null,
) {
    val isDone: Boolean get() = status == TodoStatus.Completed
    val isBlocking: Boolean get() = status == TodoStatus.Failed || status == TodoStatus.Blocked
}

enum class TodoStatus {
    /** Not started yet. */
    Pending,
    /** The harness is currently driving the model on this item. */
    InProgress,
    /** The agent reported the item finished. Shows the completion mark. */
    Completed,
    /** The item could not be completed; the loop escalates rather than hang. */
    Failed,
    /** Waiting on something outside the agent's control (approval, missing tool). */
    Blocked,
}

/**
 * A snapshot of a plan the agent generated for one request.
 *
 * `requestId` ties the plan to the user message that produced it so the UI can
 * stop showing a stale checklist once a new request begins, and [revision] is
 * bumped on every status transition so observers can tell "the plan changed"
 * from "the plan is the same list, still running".
 */
data class TodoPlan(
    val requestId: String,
    val items: List<TodoItem> = emptyList(),
    val revision: Int = 0,
) {
    val isComplete: Boolean get() = items.isNotEmpty() && items.all { it.isDone }
    val hasFailed: Boolean get() = items.any { it.isBlocking }
    val completedCount: Int get() = items.count { it.isDone }
    val progress: Float get() = if (items.isEmpty()) 0f else completedCount.toFloat() / items.size

    /** Returns a copy with [item] moved to [status], bumping the revision. */
    fun mark(itemId: String, status: TodoStatus, note: String? = null): TodoPlan {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return this
        return copy(
            items = items.toMutableList().also { list ->
                list[index] = list[index].copy(status = status, note = note ?: list[index].note)
            },
            revision = revision + 1,
        )
    }

    /** The next item the harness should drive, or null when nothing remains. */
    fun nextPending(): TodoItem? = items.firstOrNull { !it.isDone && !it.isBlocking }
}
