package com.jarves.mh.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.agent.AgentMode
import com.jarves.mh.ui.theme.AgenticAccent

/**
 * The demo's Simple / Agentic / Cooperative switch, rendered as segmented pills
 * above the composer. Disabled while a task is running, matching switchAgentMode.
 */
@Composable
fun ModePills(
    mode: AgentMode,
    onSwitch: (AgentMode) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModePill(Icons.Default.SmartToy, "Simple", mode == AgentMode.SIMPLE, enabled) { onSwitch(AgentMode.SIMPLE) }
        ModePill(Icons.Default.Group, "Agentic", mode == AgentMode.AGENTIC, enabled) { onSwitch(AgentMode.AGENTIC) }
        ModePill(Icons.Default.Checklist, "Plan-Driven", mode == AgentMode.LONGTASK, enabled) { onSwitch(AgentMode.LONGTASK) }
        ModePill(Icons.Default.Hub, "Cooperative", mode == AgentMode.COOPERATIVE, enabled) { onSwitch(AgentMode.COOPERATIVE) }
    }
}

@Composable
private fun RowScope.ModePill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 8.dp, horizontal = 10.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp).alpha(alpha),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The demo's Agentic plan card: a "Plan · N subtasks" header with one chip per
 * subtask that flips from working to done as shards land.
 */
@Composable
fun PlanCard(subtasks: List<SubtaskChip>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Plan · ${subtasks.size} subtasks",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        subtasks.forEach { chip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(10.dp).background(
                        if (chip.done) AgenticAccent else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    chip.label,
                    fontSize = 12.sp,
                    color = if (chip.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (chip.done) "done" else "working",
                    fontSize = 10.sp,
                    color = if (chip.done) AgenticAccent else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

data class SubtaskChip(val label: String, val done: Boolean)

/**
 * The demo's Cooperative peer grid: two side-by-side peer cards showing what each
 * specialist is doing before the joint integration step.
 */
@Composable
fun PeerGrid(peerA: PeerStatus, peerB: PeerStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PeerCard(peerA, Modifier.weight(1f))
        PeerCard(peerB, Modifier.weight(1f))
    }
}

data class PeerStatus(val name: String, val detail: String)

@Composable
private fun PeerCard(peer: PeerStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(peer.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(peer.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Mod 4 – the live to-do checklist for a plan-driven run.
 *
 * This is the OpenCode-style progress surface: every row is one item of the
 * plan the agent drafted, and each row flips to a green check the moment the
 * harness marks it [TodoStatus.Completed]. The header carries a progress bar so
 * a long task reads as "3 of 7" rather than an undifferentiated spinner, and a
 * failed row shows its reason instead of silently stalling the list.
 */
@Composable
fun TodoChecklist(plan: com.jarves.mh.agent.TodoPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, AgenticAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Checklist, null, tint = AgenticAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "To-do · ${plan.completedCount} of ${plan.items.size} done",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(plan.progress * 100).toInt()}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AgenticAccent,
            )
        }
        Spacer(Modifier.height(8.dp))
        // A thin progress rail under the header: the fraction that is already
        // checked off fills in mint, the remainder stays as a dim track.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(plan.progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(AgenticAccent, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        plan.items.forEach { item ->
            TodoRow(item)
        }
        if (plan.hasFailed) {
            Spacer(Modifier.height(4.dp))
            Text(
                "A step could not be completed — the plan stopped early.",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TodoRow(item: com.jarves.mh.agent.TodoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mark. A completed item shows the filled green check OpenCode uses;
        // an in-flight item shows the working dot; anything pending is an empty ring.
        when (item.status) {
            com.jarves.mh.agent.TodoStatus.Completed -> {
                Box(
                    Modifier.size(16.dp).background(AgenticAccent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = AgenticAccent, modifier = Modifier.size(11.dp))
                }
            }
            com.jarves.mh.agent.TodoStatus.InProgress -> {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            com.jarves.mh.agent.TodoStatus.Failed, com.jarves.mh.agent.TodoStatus.Blocked -> {
                Box(
                    Modifier.size(16.dp).background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            }
            com.jarves.mh.agent.TodoStatus.Pending -> {
                Box(
                    Modifier.size(12.dp).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 12.sp,
                fontWeight = if (item.status == com.jarves.mh.agent.TodoStatus.InProgress) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    item.isDone -> MaterialTheme.colorScheme.onSurfaceVariant
                    item.isBlocking -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (item.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(note, fontSize = 9.5.sp, color = AgenticAccent.copy(alpha = 0.85f))
            }
        }
        Text(
            when (item.status) {
                com.jarves.mh.agent.TodoStatus.Completed -> "done"
                com.jarves.mh.agent.TodoStatus.InProgress -> "working"
                com.jarves.mh.agent.TodoStatus.Failed -> "failed"
                com.jarves.mh.agent.TodoStatus.Blocked -> "blocked"
                com.jarves.mh.agent.TodoStatus.Pending -> "queued"
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                item.isDone -> AgenticAccent
                item.isBlocking -> MaterialTheme.colorScheme.error
                item.status == com.jarves.mh.agent.TodoStatus.InProgress -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
