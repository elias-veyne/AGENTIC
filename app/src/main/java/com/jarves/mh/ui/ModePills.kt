package com.jarves.mh.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.agent.AgentMode
import com.jarves.mh.ui.theme.Typography

@Composable
fun ModePills(
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(AgentMode.SIMPLE, AgentMode.AGENTIC, AgentMode.COOPERATIVE)
    val selectedWidth by animateDpAsState(
        targetValue = if (selectedMode != AgentMode.SIMPLE) 120.dp else 90.dp,
        label = "selectedWidth"
    )
    
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selectedMode
            val width by animateDpAsState(
                targetValue = if (isSelected) selectedWidth else 90.dp,
                label = "modeWidth"
            )
            val color = if (isSelected) DemoColors.primary else DemoColors.textMuted
            val bgAlpha = if (isSelected) 0.1f else 0f
            val alpha by animateFloatAsState(targetValue = if (isSelected) 1f else 0.6f, label = "alpha")
            
            Box(
                modifier = Modifier
                    .width(width)
                    .clip(RoundedCornerShape(13.dp))
                    .background(DemoColors.surface.copy(alpha = bgAlpha))
                    .border(
                        width = 1.dp,
                        color = if (isSelected) DemoColors.primary.copy(alpha = 0.5f) else DemoColors.border,
                        shape = RoundedCornerShape(13.dp)
                    )
                    .clickable { onModeSelected(mode) }
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = modeIcon(mode),
                        contentDescription = mode.name,
                        tint = color,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
private fun modeIcon(mode: AgentMode): ImageVector {
    return when (mode) {
        AgentMode.SIMPLE -> ImageVector.vector(
            "Simple",
            24f, 24f,
            path = {
                moveTo(21f, 11.5f)
                lineTo(20.1f, 15.3f)
                curveTo(19.1f, 18.3f, 16.3f, 20.7f, 13.4f, 21.7f)
                curveTo(11.5f, 22.4f, 9.5f, 22.4f, 7.6f, 21.7f)
                curveTo(6.1f, 21.2f, 4.7f, 20.4f, 3.5f, 19.3f)
                lineTo(5.4f, 13.6f)
                curveTo(6.3f, 10.6f, 8.5f, 8.1f, 11.4f, 7.1f)
                curveTo(13.3f, 6.4f, 15.3f, 6.4f, 17.2f, 7.1f)
                curveTo(19.7f, 8.1f, 21.7f, 10.1f, 22.7f, 12.6f)
                lineTo(22.7f, 13.1f)
            }
        )
        AgentMode.AGENTIC -> ImageVector.vector(
            "Agentic",
            24f, 24f,
            path = {
                circle(12f, 6f, 2.4f)
                circle(5f, 18f, 2.4f)
                circle(19f, 18f, 2.4f)
                moveTo(12f, 8.4f)
                lineTo(12f, 11.6f)
                lineTo(6.6f, 16f)
                lineTo(12f, 11.6f)
                lineTo(17.4f, 16f)
            }
        )
        AgentMode.COOPERATIVE -> ImageVector.vector(
            "Cooperative",
            24f, 24f,
            path = {
                circle(9f, 8f, 3.2f)
                circle(17f, 10f, 2.6f)
                arcTo(2.5f, 20f, 6.5f, 6.5f, 0f, 0f, 1f, 13f, 20f)
                arcTo(15f, 20f, 5f, 5f, 0f, 0f, 1f, 22f, 20f)
            }
        )
    }
}

private fun ImageVector.vector(name: String, width: Float, height: Float, path: Path.() -> Unit): ImageVector {
    return ImageVector.Builder(name, width, height).path(path).build()
}
