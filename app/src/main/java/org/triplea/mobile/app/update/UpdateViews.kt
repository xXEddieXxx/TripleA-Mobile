package org.triplea.mobile.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.ui.AppDialog

/** The release a card is about: offered, downloading, or a download that failed. */
private val UpdateState.release: AppRelease?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Failed -> release
        else -> null
    }

private fun megabytes(bytes: Long) = "${(bytes + 524_288) / 1_048_576} MB"

/** "Update available" on the start screen: update, later (this visit) or skip that version. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var later by rememberSaveable { mutableStateOf(false) }
    var showChanges by rememberSaveable { mutableStateOf(false) }
    // an automatic check that failed is not worth a card; a failed download is
    val release = state.release ?: return
    // switched off in Settings: the offer stays on About only
    if (!settings.checkForUpdates && state !is UpdateState.Downloading) return
    if (later && state !is UpdateState.Downloading) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            UpdateHeader(Icons.Filled.SystemUpdate, "Update available", "${AppUpdater.installedVersion} → ${release.version} · ${megabytes(release.size)}")
            UpdateProgress(state)
            // Skip is in the "What's new" dialog: four buttons do not fit a phone's width
            UpdateButtons(state, release, showSkip = false) {
                TextButton(onClick = { later = true }) { Text("Later") }
                TextButton(onClick = { showChanges = true }) { Text("What's new") }
            }
        }
    }
    if (showChanges) ChangesDialog(release) { showChanges = false }
}

/** The update part of the About screen: the same offer, or the current state and "check now". */
@Composable
fun UpdateSection() {
    val state by AppUpdater.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var showChanges by rememberSaveable { mutableStateOf(false) }
    val release = state.release
    if (release != null) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            UpdateHeader(Icons.Filled.SystemUpdate, "Version ${release.version} is available", megabytes(release.size))
            UpdateProgress(state)
            UpdateButtons(state, release) { TextButton(onClick = { showChanges = true }) { Text("What's new") } }
        }
        if (showChanges) ChangesDialog(release) { showChanges = false }
        return
    }
    val status = when {
        !AppUpdater.canUpdate -> "This build was made locally and does not look for updates."
        state is UpdateState.Checking -> "Looking for a newer version..."
        state is UpdateState.UpToDate -> "You have the newest version."
        state is UpdateState.Failed -> "Could not check: ${(state as UpdateState.Failed).message}"
        !settings.checkForUpdates -> "Automatic checks are off (Settings)."
        else -> "Looks for a newer version once a day."
    }
    Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { UpdateHeader(Icons.Filled.SystemUpdate, "App updates", status) }
        if (AppUpdater.canUpdate) {
            TextButton(onClick = { AppUpdater.check(force = true) }, enabled = state !is UpdateState.Checking) { Text("Check now") }
        }
    }
}

@Composable
private fun UpdateHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Progress bar while downloading, the error after a failed download. */
@Composable
private fun UpdateProgress(state: UpdateState) {
    when (state) {
        is UpdateState.Downloading -> Column(Modifier.padding(start = 56.dp, top = 12.dp, end = 8.dp)) {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${megabytes(state.bytes)} of ${megabytes(state.release.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        is UpdateState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 56.dp, top = 8.dp),
        )
        else -> {}
    }
}

/** One right-aligned row: skip, [middle] (later / what's new), update; nothing while downloading. */
@Composable
private fun UpdateButtons(state: UpdateState, release: AppRelease, showSkip: Boolean = true, middle: @Composable () -> Unit) {
    if (state is UpdateState.Downloading) {
        Spacer(Modifier.size(12.dp))
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSkip) TextButton(onClick = { AppUpdater.skip(release) }) { Text("Skip") }
        middle()
        UpdateButton(state, release)
    }
}

@Composable
private fun UpdateButton(state: UpdateState, release: AppRelease, onClick: () -> Unit = {}) {
    FilledTonalButton(onClick = { AppUpdater.download(release); onClick() }) {
        Text(
            when {
                state is UpdateState.Failed -> "Retry"
                state is UpdateState.Available && state.downloaded -> "Install"
                else -> "Update"
            },
        )
    }
}

/** The changes since the installed version, loaded from GitHub when it opens; skip and update from here too. */
@Composable
private fun ChangesDialog(release: AppRelease, onDismiss: () -> Unit) {
    val state by AppUpdater.state.collectAsState()
    val changes by produceState<Result<List<Change>>?>(null, release) {
        value = runCatching { AppUpdater.changes(release) }
    }
    AppDialog(
        title = "What's new in ${release.version}",
        subtitle = "Since your version ${AppUpdater.installedVersion}",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = { AppUpdater.skip(release); onDismiss() }) { Text("Skip this version") }
            Spacer(Modifier.weight(1f))
            // closes the dialog so the card's progress bar shows
            UpdateButton(state, release, onClick = onDismiss)
        },
    ) {
        val result = changes
        when {
            result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading the changes...", style = MaterialTheme.typography.bodyMedium)
            }
            result.isFailure -> Text(
                "The list of changes could not be loaded (${result.exceptionOrNull()?.message}). The update itself works anyway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.getOrThrow().isEmpty() -> Text("Small internal improvements.", style = MaterialTheme.typography.bodyMedium)
            else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val groups = result.getOrThrow().groupBy { it.kind }
                listOf(Change.Kind.NEW to "New", Change.Kind.FIXED to "Fixed", Change.Kind.CHANGED to "Changed").forEach { (kind, heading) ->
                    val items = groups[kind] ?: return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        items.forEach { change ->
                            Row {
                                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                                Text(change.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
