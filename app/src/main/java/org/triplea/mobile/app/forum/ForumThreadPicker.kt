package org.triplea.mobile.app.forum

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.forum.GameSearch
import org.triplea.mobile.forum.NodeBbClient.GameThread

/**
 * Picks an existing forum thread for a new game: the player's own threads and the forum's open
 * games, searchable, those opened in the app most recently first.
 */
@Composable
fun ForumThreadPicker(onPick: (GameThread) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val loaded by produceState<Result<List<GameThread>>?>(null) {
        value = runCatching {
            val forum = ForumAccount.selected.value
            val me = ForumAccount.verify(forum) ?: error("Sign in to ${forum.label} under Play by Forum first.")
            val client = ForumAccount.client(forum) ?: error("Sign in to ${forum.label} under Play by Forum first.")
            withContext(Dispatchers.IO) {
                (client.threadsOf(me) + client.browse()).distinctBy { it.topicId() }
                    // recently opened here first (never opened: 0), then by their last post
                    .sortedWith(compareByDescending<GameThread> { ForumThreadState.seen(it) }.thenByDescending { it.lastPost() })
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use an existing thread") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search games, players, nations") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val result = loaded
                when {
                    result == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    result.isFailure -> Text("Could not reach the forum: ${result.exceptionOrNull()?.message}", Modifier.padding(top = 12.dp))
                    else -> {
                        val all = result.getOrThrow()
                        val shown = remember(all, query) { GameSearch.matching(all, query, true, Instant.now()) }
                        if (shown.isEmpty()) {
                            Text(if (query.isBlank()) "No forum threads found." else "No thread matches \"$query\".", Modifier.padding(top = 12.dp))
                        }
                        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                            items(shown, key = { it.topicId() }) { thread ->
                                Column(Modifier.fillMaxWidth().clickable { onPick(thread) }.padding(vertical = 10.dp)) {
                                    Text(thread.title(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val details = listOfNotNull(
                                        thread.lastTurn().ifEmpty { null },
                                        thread.lastPosterSlug().ifEmpty { null }?.let { "last post by $it" },
                                        DateUtils.getRelativeTimeSpanString(thread.lastPost().toEpochMilli()).toString(),
                                    )
                                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
