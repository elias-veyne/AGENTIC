package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.data.ConfiguredModel
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.ProviderKind

/**
 * The "Add new model" sheet.
 *
 * AGENTIC is multi-model: this is where a second (or third, …) provider gets
 * wired up. Each entry keeps its own provider, base URL, model id and API key,
 * independent of every other entry — so adding a key never overwrites the one
 * already in use, which is the whole point of the registry.
 *
 * Pass [editing] to reuse the same form for reconfiguring an existing model;
 * leave it null for the fresh-add flow (the key is then required).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelSheet(
    editing: ConfiguredModel?,
    existingLabels: List<String>,
    onSave: (label: String, kind: ProviderKind, baseUrl: String, model: String, secret: String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val providers = remember { DEEPSEEK_HARNESS_PROVIDERS.toList() }
    var kind by rememberSaveable { mutableStateOf(editing?.kind ?: providers.first()) }
    var label by rememberSaveable { mutableStateOf(editing?.label.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(editing?.baseUrl.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(editing?.model.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf("") }
    var secretTouched by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Fixed providers (OpenCode Zen, NVIDIA NIM) ignore the base URL field entirely.
    val baseUrlFixed = kind.fixedBaseUrl
    val effectiveBaseUrl = if (baseUrlFixed) kind.defaultBaseUrl else baseUrl
    val effectiveLabel = label.trim().ifBlank { kind.title }
    val effectiveModel = model.trim().ifBlank { kind.defaultModel }
    val duplicateLabel = effectiveLabel.isNotEmpty() &&
        existingLabels.any { it.equals(effectiveLabel, ignoreCase = true) && it != editing?.label }
    // A fresh add needs a key; an edit may keep the stored one by leaving this blank.
    val secretMissing = secret.isBlank() && editing == null
    val canSave = !duplicateLabel && !secretMissing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (editing == null) "Add new model" else "Edit model",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Each model keeps its own base URL and API key, so several providers " +
                    "can run side by side without replacing the one already in use.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))

            // ── Provider chooser: never just the one currently in use ──
            Text(
                "Provider",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providers.forEach { provider ->
                    FilterChip(
                        selected = kind == provider,
                        onClick = { kind = provider },
                        label = { Text(provider.title) },
                    )
                }
            }
            if (kind.experimental) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${kind.subtitle} · experimental",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                placeholder = { Text(kind.title) },
                singleLine = true,
                isError = duplicateLabel,
                supportingText = if (duplicateLabel) {
                    { Text("You already have a model called “$effectiveLabel”", fontSize = 12.sp) }
                } else {
                    { Text("Shown in the model picker, e.g. “Work DeepSeek”", fontSize = 12.sp) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = effectiveBaseUrl,
                onValueChange = if (baseUrlFixed) ({}) else ({ baseUrl = it }),
                label = { Text("Base URL") },
                placeholder = { Text(kind.defaultBaseUrl) },
                singleLine = true,
                enabled = !baseUrlFixed,
                supportingText = if (baseUrlFixed) {
                    { Text("Fixed by ${kind.title}", fontSize = 12.sp) }
                } else {
                    { Text("Override only if your endpoint differs from the default", fontSize = 12.sp) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model id") },
                placeholder = { Text(kind.defaultModel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it; secretTouched = true },
                label = {
                    Text(if (editing == null) "API key" else "API key (leave blank to keep)")
                },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (showSecret) "Hide key" else "Show key",
                        )
                    }
                },
                isError = secretMissing && secretTouched,
                supportingText = if (secretMissing && secretTouched) {
                    { Text("Enter an API key for ${kind.title}", fontSize = 12.sp) }
                } else {
                    { Text("Encrypted in Android secure storage", fontSize = 12.sp) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onSave(effectiveLabel, kind, effectiveBaseUrl, effectiveModel, secret.trim()) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (editing == null) "Add model" else "Save changes")
            }

            if (editing != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove this model", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (confirmRemove && editing != null) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("Remove model?") },
                text = {
                    Text("“${editing.label}” will be disconnected and its API key deleted from secure storage.")
                },
                confirmButton = {
                    TextButton(onClick = { onRemove(editing.id) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * The registry section shown at the top of the per-chat model picker.
 *
 * Renders the flat list of configured models — the first is the one in use by
 * default — plus the "Add new model" affordance. Selecting a row pins the chat
 * to that whole entry (URL + key + model); the pencil reopens [AddModelSheet]
 * in edit mode.
 */
@Composable
fun ModelRegistryList(
    models: List<ConfiguredModel>,
    activeModelId: String?,
    onSelect: (String) -> Unit,
    onEdit: (ConfiguredModel) -> Unit,
    onAddNew: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Key,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Your models",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAddNew, contentPadding = PaddingValues(
            horizontal = 8.dp, vertical = 0.dp,
        )) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add new model", fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(4.dp))

    if (models.isEmpty()) {
        // First-run state: nothing configured yet, so the add CTA is the whole card.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    RoundedCornerShape(12.dp),
                )
                .clickable { onAddNew() }
                .padding(horizontal = 14.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("No models yet", fontWeight = FontWeight.SemiBold)
                Text(
                    "Add your first model to start chatting",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        models.forEach { entry ->
            RegistryModelRow(
                entry = entry,
                selected = activeModelId == entry.id,
                onSelect = { onSelect(entry.id) },
                onEdit = { onEdit(entry) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun RegistryModelRow(
    entry: ConfiguredModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "IN USE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.kind.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · ${entry.displayModel}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Link,
                    null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.displayBaseUrl,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Default.Edit,
                "Edit ${entry.label}",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RegistryRadio(selected = selected)
    }
}

@Composable
private fun RegistryRadio(selected: Boolean) {
    Box(
        Modifier.size(20.dp).border(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}
