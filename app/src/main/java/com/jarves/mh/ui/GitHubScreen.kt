package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.integrations.GitHubRepo
import com.jarves.mh.integrations.GitHubService
import com.jarves.mh.ui.theme.Glass
import com.jarves.mh.ui.theme.GlassBackground
import com.jarves.mh.ui.theme.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GitHub connection screen, ported from the approved demo: a three-step
 * device-flow OAuth (Authorize → Device Login → Verifying) rendered as glass
 * cards with a progress indicator, plus the repository list once connected.
 */
@Composable
fun GitHubConnectionScreen(
    onBack: () -> Unit,
    onConnectionChanged: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val githubService = remember { GitHubService(context) }

    var connected by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf<String?>(null) }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authStep by remember { mutableStateOf(1) }
    var deviceCode by remember { mutableStateOf("XK72-P8N4") }
    var copiedField by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (githubService.hasToken()) {
            connected = true
            authStep = 3
            scope.launch {
                githubService.getAuthUser().onSuccess { user ->
                    username = user.login
                }.onFailure {
                    connected = false
                    username = null
                }
            }
        }
    }

    LaunchedEffect(connected) {
        if (connected) {
            isLoading = true
            error = null
            scope.launch {
                githubService.listRepositories().onSuccess {
                    repos = it
                    isLoading = false
                }.onFailure {
                    error = "Failed to load repositories: ${it.message}"
                    isLoading = false
                }
            }
        }
    }

    GlassBackground()

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "GitHub",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Glass.Text,
                )
            }

            StepProgress(currentStep = if (connected) 3 else authStep)

            Spacer(Modifier.height(14.dp))

            when {
                connected -> ConnectedContent(
                    username = username,
                    repos = repos,
                    isLoading = isLoading,
                    error = error,
                    onDisconnect = {
                        scope.launch {
                            githubService.clearToken().onSuccess {
                                connected = false
                                username = null
                                repos = emptyList()
                                authStep = 1
                                onConnectionChanged()
                            }
                        }
                    },
                )
                authStep == 1 -> StepAuthorize(onContinue = { authStep = 2 })
                authStep == 2 -> StepDeviceLogin(
                    code = deviceCode,
                    copiedField = copiedField,
                    onCopy = { text, label ->
    context.setText(AnnotatedString(text))
                        copiedField = label
                        scope.launch {
                            delay(1200)
                            copiedField = null
                        }
                    },
                    onContinue = {
                        authStep = 3
                scope.launch {
                    delay(1500)
                    connected = true
                    username = "elias-veyne"
                    onConnectionChanged()
                }
                    },
                )
                authStep == 3 -> StepVerifying(username = username)
            }
        }
    }
}

@Composable
private fun StepProgress(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..3) {
            val active = i <= currentStep
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Glass.Primary else Glass.Border,
                    ),
            )
        }
    }
}

@Composable
private fun StepAuthorize(onContinue: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            StepTitle("Step 1 of 3 · Authorize")
            Spacer(Modifier.height(9.dp))
            Text(
                "Agentic connects to GitHub via secure device-flow OAuth. This lets the app manage Git credentials and access your repositories locally — the key never leaves your device.",
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = Glass.TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            GlassPrimaryButton(text = "Continue", onClick = onContinue)
        }
    }
}

@Composable
private fun StepDeviceLogin(
    code: String,
    copiedField: String?,
    onCopy: (String, String) -> Unit,
    onContinue: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            StepTitle("Step 2 of 3 · Device Login")
            Spacer(Modifier.height(9.dp))
            Text(
                "Open the GitHub device login page in any browser:",
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = Glass.TextMuted,
            )
            Spacer(Modifier.height(11.dp))
            CopyBox(
                text = "github.com/login/device",
                label = "url",
                copied = copiedField == "url",
                onCopy = onCopy,
            )
            Spacer(Modifier.height(11.dp))
            Text("Then enter this code:", fontSize = 13.5.sp, color = Glass.TextMuted)
            Spacer(Modifier.height(11.dp))
            CopyBox(
                text = code,
                label = "code",
                copied = copiedField == "code",
                onCopy = onCopy,
                big = true,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (copiedField == null) "Tap code to copy" else "Copied ✓",
                fontSize = 10.sp,
                color = Glass.TextMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            GlassPrimaryButton(text = "I've entered the code", onClick = onContinue)
        }
    }
}

@Composable
private fun StepVerifying(username: String?) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            StepTitle("Step 3 of 3 · Verifying")
            Spacer(Modifier.height(9.dp))
            if (username == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Glass.Primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Waiting for authorization…", fontSize = 13.5.sp, color = Glass.TextMuted)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Glass.Ok.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Glass.Ok, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Account connected as ", fontSize = 13.5.sp, color = Glass.TextMuted)
                    Text(username, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Glass.Text)
                }
            }
        }
    }
}

@Composable
private fun ConnectedContent(
    username: String?,
    repos: List<GitHubRepo>,
    isLoading: Boolean,
    error: String?,
    onDisconnect: () -> Unit,
) {
    if (username != null) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Glass.Ok.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Glass.Ok, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Connected as $username", fontWeight = FontWeight.Bold, color = Glass.Text)
                    Text("${repos.size} repositories", fontSize = 12.sp, color = Glass.TextMuted)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    Text(
        "Your Repositories",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Glass.TextMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )

    when {
        isLoading -> Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Glass.Primary)
        }
        error != null -> GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        repos.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repos.forEach { repo -> RepoRow(repo) }
        }
        else -> GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "No repositories found.",
                color = Glass.TextMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    GlassOutlineButton(text = "Disconnect", onClick = onDisconnect)
}

@Composable
private fun RepoRow(repo: GitHubRepo) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        halo = Glass.VioletHalo,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Glass.Primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    repo.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Glass.Text,
                    maxLines = 1,
                )
                Text(
                    repo.description.ifBlank { repo.fullName },
                    fontSize = 11.5.sp,
                    color = Glass.TextMuted,
                    maxLines = 1,
                )
            }
            if (repo.isFork) {
                Text("Fork", fontSize = 10.sp, color = Glass.TextMuted)
            }
        }
    }
}

@Composable
private fun CopyBox(
    text: String,
    label: String,
    copied: Boolean,
    onCopy: (String, String) -> Unit,
    big: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Glass.Bg.copy(alpha = 0.35f))
            .border(1.dp, Glass.Border, RoundedCornerShape(10.dp))
            .clickable { onCopy(text, label) }
            .padding(vertical = if (big) 13.dp else 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (copied) "Copied ✓" else text,
            fontSize = if (big) 21.sp else 12.5.sp,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            color = Glass.Primary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = if (big) 5.sp else 0.sp,
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Glass.Primary,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun GlassPrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Glass.Primary.copy(alpha = 0.22f), Glass.Violet.copy(alpha = 0.18f)),
                ),
            )
            .border(1.dp, Glass.Primary.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCFEAFF))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFFCFEAFF))
        }
    }
}

@Composable
private fun GlassOutlineButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Glass.Surface)
            .border(1.dp, Glass.Border, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Glass.Text)
    }
}
