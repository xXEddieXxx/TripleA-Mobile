package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.online.Social

private val log = LoggerFactory.getLogger("DirectChatScreen")

/** The friends' chat channels (null until loaded), the last load error and a [retry] of the load. */
internal class FriendChannels(val channels: Map<String, String>?, val error: String?, val retry: () -> kotlin.Unit)

/**
 * Loads the friends' channels whenever none are loaded: a direct route or a restored process skipped the
 * Online screen, or a logout/login dropped them. [refresh] also fetches once when a list is loaded (it stays shown meanwhile).
 */
@Composable
internal fun rememberFriendChannels(refresh: Boolean = false): FriendChannels {
    val channels by Social.channels.collectAsState()
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // not a key: taking the owed refresh must not restart the load
    val refreshOwed = remember { booleanArrayOf(refresh) }
    LaunchedEffect(channels == null, attempt) {
        if (channels != null && !refreshOwed[0]) return@LaunchedEffect
        val force = refreshOwed[0]
        refreshOwed[0] = false
        loadError = null
        loadError = withContext(Dispatchers.IO) {
            runCatching { Social.loadFriends(OnlineApi.forUrl(AppSettings.current.serverUrl), force) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }
    return FriendChannels(channels, loadError) { attempt++ }
}

/** A failed friends list load and its "Retry" button. */
@Composable
internal fun LoadFailed(error: String, onRetry: () -> kotlin.Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(error)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** The private chat with friend [username] (online mode); everything shown is marked read while the screen is resumed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatScreen(username: String, onBack: () -> kotlin.Unit) {
    val friends = rememberFriendChannels()
    val channels = friends.channels
    val channel = channels?.get(username)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(channel) {
        if (channel == null) return@LaunchedEffect
        // only while resumed: a chat left open in the background must not swallow the unread count
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Social.chat.map { it[channel]?.lastOrNull()?.id }.filterNotNull().distinctUntilChanged().collect { id ->
                withContext(Dispatchers.IO) {
                    runCatching { Social.markRead(channel, id) }.onFailure { log.info("Mark read failed: {}", it.message) }
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(username) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)
        if (channel == null) {
            val error = friends.error
            if (error != null) LoadFailed(error, friends.retry, modifier)
            else Text(if (channels == null) "Loading…" else "$username is not your friend.", modifier)
        } else {
            ChatPanel(channel, AppSettings.tokens.tokens?.username.orEmpty(), modifier)
        }
    }
}
