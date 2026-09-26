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
                        if (chip.done) Color(0xFF4CC2A8) else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    chip.label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (chip.done) "done" else "working",
                    fontSize = 10.sp,
                    color = if (chip.done) Color(0xFF4CC2A8) else MaterialTheme.colorScheme.primary,
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
