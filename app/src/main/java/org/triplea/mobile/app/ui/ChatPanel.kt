package org.triplea.mobile.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.protocol.ChatMessage

/**
 * The chat of [channel] (online mode): history, live messages, a text field and optional [quick]
 * messages. Long-pressing someone else's message offers Block and Report, and their profile with [onOpenProfile].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(
    channel: String,
    me: String,
    modifier: Modifier = Modifier,
    quick: List<String> = emptyList(),
    onOpenProfile: ((String) -> kotlin.Unit)? = null,
) {
    val chats by Social.chat.collectAsState()
    val blocked by Social.blocked.collectAsState()
    val chatError by Social.chatError.collectAsState()
    val messages = chats[channel].orEmpty().filter { it.username !in blocked }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()

    /** Runs a blocking server call off the main thread; a failure becomes the error line, success shows [done] if given. */
    fun call(done: String? = null, block: () -> kotlin.Unit) {
        info = null
        scope.launch {
            error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } }
            if (error == null) info = done
        }
    }

    fun send(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        if (Social.send(channel, trimmed.take(MAX_CHAT_CHARS))) text = "" else error = "Not connected to the chat"
    }

    LaunchedEffect(channel) { call { Social.loadHistory(channel); Social.loadBlocks() } }
    // the newest message, not the count: a capped list keeps its size when a new one arrives
    LaunchedEffect(messages.lastOrNull()?.id) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(modifier) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.id }) { message ->
                Text(
                    "${message.username}: ${message.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { if (message.username != me && message.username != DELETED_USER) chosen = message })
                        .padding(vertical = 3.dp),
                )
            }
        }
        // a refused send belongs to its own channel; channel "" means the chat socket stopped
        (error ?: chatError?.takeIf { it.channel == channel || it.channel.isEmpty() }?.reason)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        if (quick.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.forEach { line -> AssistChip(onClick = { send(line) }, label = { Text(line) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text,
                { text = it.take(MAX_CHAT_CHARS) },
                placeholder = { Text("Message") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(text) }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send(text) }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "send")
            }
        }
    }

    chosen?.let { message ->
        var reporting by remember(message) { mutableStateOf(false) }
        var reason by remember(message) { mutableStateOf("") }
        AppDialog(
            title = message.username,
            subtitle = message.text,
            onDismiss = { chosen = null },
            buttons = {
                TextButton(onClick = { chosen = null }) { Text("Cancel") }
                if (reporting) {
                    Button(enabled = reason.isNotBlank(), onClick = {
                        chosen = null
                        call("Report sent") { Social.requireApi().report(message.username, reason.trim().take(1000), message.id) }
                    }) { Text("Send report") }
                }
            },
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (reporting) {
                    OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
                } else {
                    onOpenProfile?.let { open -> TextButton(onClick = { chosen = null; open(message.username) }) { Text("Profile") } }
                    TextButton(onClick = {
                        chosen = null
                        call { Social.requireApi().block(message.username); Social.loadBlocks() }
                    }) { Text("Block user") }
                    TextButton(onClick = { reporting = true }) { Text("Report") }
                }
            }
        }
    }
}

/**
 * Others' messages in [channel] the user has not seen: newer than what was there when the screen opened, or than the
 * newest one while the chat is [open]. 0 until the history is loaded.
 */
@Composable
internal fun rememberUnread(channel: String, me: String, open: Boolean): Int {
    val chats by Social.chat.collectAsState()
    val blocked by Social.blocked.collectAsState()
    val messages = chats[channel].orEmpty().filter { it.username !in blocked }
    val last = messages.lastOrNull()?.id
    var readUpTo by remember(channel) { mutableStateOf<Long?>(null) }
    LaunchedEffect(channel) {
        withContext(Dispatchers.IO) { runCatching { Social.loadHistory(channel) } }
        readUpTo = Social.chat.value[channel]?.lastOrNull()?.id ?: 0L
    }
    LaunchedEffect(open, last) { if (open) readUpTo = last ?: 0L }
    return readUpTo?.let { read -> messages.count { it.id > read && it.username != me } } ?: 0
}

private const val MAX_CHAT_CHARS = 500

/** The server's author name for messages of deleted accounts: nobody left to block or report. */
private const val DELETED_USER = "deleted user"
