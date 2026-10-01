package com.jarves.mh.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.jarves.mh.agent.AgentMode
import com.jarves.mh.ui.orbs.OrbPet
import com.jarves.mh.ui.orbs.OrbState

/**
 * Demo-style main screen matching the HTML prototype.
 * Shows mode pills, status cards, and chat with orbital pet.
 */
@Composable
fun DemoScreen(
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DemoColors.bg,
        topBar = {
            // Ambient background glow
            AmbientGlowBackground()
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DemoColors.bg)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // Mode selector pills
            ModePills(
                selectedMode = selectedMode,
                onModeSelected = onModeSelected
            )
            
            // Status cards (demo's stat grid)
            StatusCards()
            
            // Recent chats with pet
            RecentChatsSection()
        }
    }
}

@Composable
private fun AmbientGlowBackground() {
    // Two radial gradients matching the demo
    Canvas(modifier = Modifier.fillMaxSize()) {
        // First glow
        drawCircle(
            color = DemoColors.primary.copy(alpha = DemoColors.ambientGlow),
            radius = size.minDimension * 0.5f,
            center = Offset(size.width * 0.16f, size.height * 0.1f)
        )
        // Second glow
        drawCircle(
            color = DemoColors.primary2.copy(alpha = DemoColors.ambientGlow),
            radius = size.minDimension * 0.4f,
            center = Offset(size.width * 0.88f, size.height * 0.22f)
        )
    }
}

@Composable
private fun StatusCards() {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp)
    ) {
        // Section label
        Text(
            text = "QUICK STATS",
            color = DemoColors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 11.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stat card 1
            StatCard("Tasks", "12", "+3 today")
            StatCard("Time", "4.2h", "+12%")
            StatCard("Success", "94%", "+2%")
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, change: String) {
    Card(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DemoColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(13.dp)
        ) {
            Text(
                text = title,
                color = Color(0x99FFFFFF),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = DemoColors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = change,
                    color = DemoColors.ok,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RecentChatsSection() {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
    ) {
        Text(
            text = "RECENT CHATS",
            color = DemoColors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 11.dp)
        )
        
        // Sample chat item with pet
        ChatRow(
            title = "Refactor auth middleware",
            preview = "Backend peer finished validating the schema...",
            time = "2h",
            petState = OrbState.SOLVING
        )
        
        ChatRow(
            title = "Glass header layout",
            preview = "Header is fluid down to 360px with halo...",
            time = "Yesterday",
            petState = OrbState.COMPOSING
        )
        
        ChatRow(
            title = "Orb pet state machine",
            preview = "Mapped all 8 states to agent activity...",
            time = "Mon",
            petState = OrbState.LISTENING
        )
    }
}

@Composable
private fun ChatRow(title: String, preview: String, time: String, petState: OrbState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(DemoColors.surface.copy(alpha = 0.08f))
            .clickable { }
            .padding(13.dp)
    ) {
        // Avatar with pet
        Box(
            modifier = Modifier
                .size(37.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(DemoColors.surface)
        ) {
            OrbPet(
                state = petState,
                modifier = Modifier.size(24.dp).align(Alignment.Center)
            )
        }
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = DemoColors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = preview,
                color = DemoColors.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1
            )
        }
        
        Text(
            text = time,
            color = DemoColors.textMuted,
            fontSize = 10.sp
        )
    }
}
