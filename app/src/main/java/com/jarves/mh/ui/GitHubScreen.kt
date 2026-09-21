package com.jarves.mh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarves.mh.integrations.GitHubService
import com.jarves.mh.integrations.GitHubRepo
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubConnectionScreen(
    onBack: () -> Unit,
    onConnectionChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val githubService = remember { GitHubService(context) }

    var connected by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf<String?>(null) }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }

    // Check initial connection status
    LaunchedEffect(Unit) {
        if (githubService.hasToken()) {
            connected = true
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

    // Load user repos when connected
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (connected) {
                        IconButton(onClick = { showTokenDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Connection status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (connected) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (connected) "Connected as $username" else "Not connected to GitHub",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connect/Disconnect button
            Button(
                onClick = {
                    if (connected) {
                        scope.launch {
                            githubService.clearToken().onSuccess {
                                connected = false
                                username = null
                                repos = emptyList()
                                onConnectionChanged()
                            }
                        }
                    } else {
                        showTokenDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (connected) Icons.Default.Delete else Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (connected) "Disconnect" else "Connect to GitHub")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Repositories list
            Text(
                text = "Your Repositories (${repos.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (repos.isNotEmpty()) {
                Column {
                    repos.forEach { repo ->
                        RepoItem(repo = repo)
                    }
                }
            } else if (!isLoading) {
                Text(
                    text = "No repositories found or you don't have access to any.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error message
            if (error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    // Token input dialog
    if (showTokenDialog) {
        GitHubTokenDialog(
            onDismiss = { showTokenDialog = false },
            onTokenSubmitted = { token ->
                scope.launch {
                    githubService.saveToken(token).onSuccess {
                        connected = true
                        onConnectionChanged()
                        showTokenDialog = false
                    }.onFailure {
                        error = "Failed to save token: ${it.message}"
                    }
                }
            }
        )
    }
}

@Composable
fun RepoItem(repo: GitHubRepo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (repo.visibility == "private") Icons.Default.Lock else Icons.Default.Visibility,
                    contentDescription = repo.visibility,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = repo.description.ifEmpty { "No description" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Updated: ${repo.pushedAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubTokenDialog(
    onDismiss: () -> Unit,
    onTokenSubmitted: (String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var showInstructions by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub Access Token") },
        text = {
            Column {
                if (showInstructions) {
                    Text(
                        text = "To connect GitHub, you need to create a Personal Access Token:\n\n" +
                                "1. Go to github.com/settings/tokens\n" +
                                "2. Click 'Generate new token (classic)'\n" +
                                "3. Give it a name and select 'repo' scope\n" +
                                "4. Generate and copy the token\n\n" +
                                "Note: The token is encrypted and stored securely on your device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = { showInstructions = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Enter token")
                    }
                } else {
                    Text(
                        text = "Paste your GitHub Personal Access Token below:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (token.isNotBlank()) {
                        onTokenSubmitted(token)
                    }
                },
                enabled = token.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
