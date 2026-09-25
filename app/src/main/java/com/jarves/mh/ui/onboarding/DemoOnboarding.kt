package com.jarves.mh.ui.onboarding

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.jarves.mh.agent.AgentMode
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.ui.orbs.OrbPet
import com.jarves.mh.ui.orbs.OrbState

/**
 * The demo's 4-step first-run flow — mode, model, GitHub, then "Setting things
 * up" — where the final step drives the app's real permission/workspace setup
 * instead of only animating. [onSetupComplete] fires once every checklist row
 * has resolved.
 */
@Composable
fun DemoOnboarding(
    initialMode: AgentMode,
    providers: List<ProviderKind>,
    onModeChosen: (AgentMode) -> Unit,
    onProviderChosen: (ProviderProfile, String) -> Unit,
    onSkipProvider: () -> Unit,
    onConnectGitHub: () -> Unit,
    githubLogin: String?,
    githubUserCode: String?,
    notificationsAllowed: () -> Boolean,
    batteryUnrestricted: () -> Boolean,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onPrepareWorkspace: suspend () -> Unit,
    onSetupComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    when (step) {
        0 -> ModeStep(
            selected = initialMode,
            onChoose = {
                onModeChosen(it)
                step = 1
            },
            onSkip = { step = 1 },
        )
        1 -> ProviderStep(
            providers = providers,
            onChosen = { profile, key ->
                onProviderChosen(profile, key)
                step = 2
            },
            onSkip = {
                onSkipProvider()
                step = 2
            },
        )
        2 -> GitHubStep(
            onConnect = {
                onConnectGitHub()
                step = 3
            },
            onSkip = { step = 3 },
            githubLogin = githubLogin,
        )
        3 -> SetupStep(
            notificationsAllowed = notificationsAllowed,
            batteryUnrestricted = batteryUnrestricted,
            onRequestNotifications = onRequestNotifications,
            onRequestBattery = onRequestBattery,
            onPrepareWorkspace = onPrepareWorkspace,
            onSetupComplete = onSetupComplete,
        )
    }
}

@Composable
private fun OnboardingScaffold(
    step: Int,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(48.dp))
        StepIndicator(step)
        Spacer(Modifier.height(26.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StepIndicator(currentStep: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { index ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (index == currentStep) 26.dp else 12.dp)
                    .background(
                        if (index <= currentStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        if (selected) {
            Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun ModeStep(
    selected: AgentMode,
    onChoose: (AgentMode) -> Unit,
    onSkip: () -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf(selected) }
    OnboardingScaffold(
        step = 0,
        title = "Choose how Agentic works",
        subtitle = "You can switch this any time from the composer.",
        actions = {
            PrimaryButton(text = "Continue") { onChoose(picked) }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            ChoiceRow(Icons.Default.SmartToy, "Simple", "One request, one streamed reply. Fast and direct.", picked == AgentMode.SIMPLE) { picked = AgentMode.SIMPLE }
            ChoiceRow(Icons.Default.Group, "Agentic", "Breaks your task into subtasks, assigns workers, integrates the result.", picked == AgentMode.AGENTIC) { picked = AgentMode.AGENTIC }
            ChoiceRow(Icons.Default.Group, "Cooperative", "Specialist peers work in parallel, share context, integrate together.", picked == AgentMode.COOPERATIVE) { picked = AgentMode.COOPERATIVE }
        }
    }
}

@Composable
private fun ProviderStep(
    providers: List<ProviderKind>,
    onChosen: (ProviderProfile, String) -> Unit,
    onSkip: () -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf<ProviderKind?>(null) }
    var apiKey by rememberSaveable { mutableStateOf("") }

    OnboardingScaffold(
        step = 1,
        title = "Connect your models",
        subtitle = "Agentic talks to LLMs through your own API keys. Stored on-device, never sent anywhere else.",
        actions = {
            if (picked != null && apiKey.isNotBlank()) {
                PrimaryButton("Save & Continue") {
                    val kind = requireNotNull(picked)
                    onChosen(ProviderProfile(kind, model = kind.defaultModel, baseUrl = kind.defaultBaseUrl), apiKey)
                }
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("I'll do this later", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            providers.forEach { kind ->
                ChoiceRow(Icons.Default.SmartToy, kind.displayName, kind.description, picked == kind) { picked = kind }
            }
        }
        if (picked != null) {
            Spacer(Modifier.height(6.dp))
            Text("API key", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("sk-…") },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun GitHubStep(
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    githubLogin: String?,
) {
    OnboardingScaffold(
        step = 2,
        title = "Connect GitHub",
        subtitle = "Let agents read and modify your repositories. Device-flow OAuth — the key never leaves your device.",
        actions = {
            PrimaryButton(text = if (githubLogin != null) "Connected — Continue" else "Connect GitHub") { onConnect() }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("GitHub Account", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    githubLogin ?: "Not connected",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(githubLogin != null, if (githubLogin != null) "On" else "Off")
        }
        if (githubUserCode != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Enter the code at github.com/login/device:",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                githubUserCode.orEmpty(),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SetupStep(
    notificationsAllowed: () -> Boolean,
    batteryUnrestricted: () -> Boolean,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onPrepareWorkspace: suspend () -> Unit,
    onSetupComplete: () -> Unit,
) {
    var notifDone by remember { mutableStateOf(notificationsAllowed()) }
    var batteryDone by remember { mutableStateOf(batteryUnrestricted()) }
    var workspaceDone by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // Notifications and battery follow the demo's "tap a row" interaction.
        // The workspace row prepares on entry so the flow lands on Home ready.
        onPrepareWorkspace()
        workspaceDone = true
        notifDone = notificationsAllowed()
        batteryDone = batteryUnrestricted()
        if (notifDone && batteryDone && workspaceDone) {
            delay(500)
            running = false
            onSetupComplete()
        }
    }

    OnboardingScaffold(
        step = 3,
        title = "Setting things up",
        subtitle = "A few system permissions so agents can notify you and keep working in the background.",
        actions = {
            PrimaryButton(
                text = if (running) "Finishing…" else "Start using Agentic",
                enabled = !running,
                onClick = {
                    notifDone = notificationsAllowed()
                    batteryDone = batteryUnrestricted()
                    if (notifDone && batteryDone) onSetupComplete()
                },
            ),
        },
    ) {
        SetupRow(Icons.Default.Notifications, "Notifications", "Alerts when long tasks finish or need approval.", notifDone, onRequestNotifications)
        Spacer(Modifier.height(10.dp))
        SetupRow(Icons.Default.BatterySaver, "Battery optimization", "Exclude Agentic so agents survive backgrounding.", batteryDone, onRequestBattery)
        Spacer(Modifier.height(10.dp))
        SetupRow(Icons.Default.Folder, "Workspace", "Creating the local folder for agent sessions.", workspaceDone) {}
    }
}

@Composable
private fun SetupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    complete: Boolean,
    onTap: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        if (complete) {
            Box(Modifier.size(22.dp).background(Color(0xFF4CC2A8), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onTap) { Text("Allow", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun StatusPill(on: Boolean, label: String) {
    Row(
        Modifier.background(if (on) Color(0xFF4CC2A8).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (on) Color(0xFF4CC2A8) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
    }
}
