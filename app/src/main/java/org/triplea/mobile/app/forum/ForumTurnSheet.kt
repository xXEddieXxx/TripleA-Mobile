package org.triplea.mobile.app.forum

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.triplea.mobile.app.ui.InfoNote
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.game.DiceFailedRequest
import org.triplea.mobile.app.game.ForumTurnRequest
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.game.ManualForumPost
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.NodeBbClient.GameThread
import org.triplea.mobile.forum.PbfPost
import org.triplea.mobile.forum.TurnPoster

/**
 * The end of a forum turn: the summary, a comment with pictures, then Keep playing (the next
 * nation is also the player's) or Send. A failed send keeps the text, pictures and uploaded save.
 */
@Composable
fun ForumTurnSheet(request: ForumTurnRequest, onSent: (GameThread, heldBack: Boolean) -> Unit) {
    var poster by remember(request) { mutableStateOf<TurnPoster?>(null) }
    ForumPostDialog(
        key = request,
        stepTitle = "Turn Summary",
        player = request.player.name,
        round = request.round,
        summary = request.summary,
        thread = request.thread,
        notices = listOfNotNull(
            // several nations played in a row ("Keep playing"): the post covers them all
            request.nations.takeIf { it.size > 1 }?.let { "This post covers ${it.joinToString(", ")}." },
            request.nextAlly?.let { "${request.nextAlly} plays next and is an ally of ${request.player.name}. If you play them too, tap Keep playing: their moves join this post." },
        ),
        poster = { poster ?: TurnPoster(signedInClient(request.thread.forum()), request.thread, GameController.saveForumTurn(request)).also { poster = it } },
        onSent = { onSent(request.thread, it) },
        // the way out while sending keeps failing: the save is kept, unsent, for later
        sendLater = { GameController.unsendForumTurn(request) },
    ) { enabled ->
        // once the save is uploaded the turn is posted from it: no playing on
        OutlinedButton(onClick = { request.complete(Unit) }, enabled = enabled && poster == null) { Text("Keep playing") }
    }
}

/**
 * Posts the game as it stands, at any time, as desktop's "Post PBEM/PBF Gamesave" does: the turn
 * so far, a comment and the save. The game goes on; Cancel or a send closes it.
 */
@Composable
fun ForumSavePostDialog(post: ManualForumPost, onDone: (heldBack: Boolean?) -> Unit) {
    var poster by remember(post) { mutableStateOf<TurnPoster?>(null) }
    ForumPostDialog(
        key = post,
        stepTitle = ManualForumPost.STEP_TITLE,
        player = post.player,
        round = post.round,
        summary = post.summary,
        thread = post.thread,
        poster = { poster ?: TurnPoster(signedInClient(post.thread.forum()), post.thread, GameController.saveManualForumPost(post)).also { poster = it } },
        onSent = { onDone(it) },
        sendLater = null,
        initialMessage = if (post.question.isEmpty()) "" else
            "**Your decision, please:**\n" + post.question.lines().joinToString("\n") { "> $it" } +
                "\n\nThe save below holds the game at this point; I go on from it once you answer.",
        startPicture = post.screenshot,
    ) { enabled ->
        TextButton(onClick = { onDone(null) }, enabled = enabled) { Text("Cancel") }
    }
}

private fun signedInClient(forum: Forum) =
    ForumAccount.client(forum) ?: throw IllegalStateException("Signed out of ${forum.label}. Sign in under Play by Forum.")

/**
 * A game post to its thread: title, summary, the comment editor, errors and the buttons;
 * [poster] (called off the main thread) saves and uploads once, retries reuse it.
 */
@Composable
private fun ForumPostDialog(
    key: Any,
    stepTitle: String,
    player: String,
    round: Int,
    summary: String,
    thread: GameThread,
    /** Notes above the summary, eg the nations it covers; the edit mode note is added from the summary itself. */
    notices: List<String> = emptyList(),
    poster: () -> TurnPoster,
    onSent: (heldBack: Boolean) -> Unit,
    sendLater: (() -> Unit)?,
    initialMessage: String = "",
    /** A picture to upload and add at once, eg a screenshot of the question; deleted afterwards. */
    startPicture: Path? = null,
    secondary: @Composable (enabled: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember(key) { mutableStateOf(TextFieldValue(initialMessage, TextRange(initialMessage.length))) }
    var busy by remember(key) { mutableStateOf<String?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val picture = startPicture ?: return@LaunchedEffect
        busy = "Uploading screenshot…"
        try {
            val url = withContext(Dispatchers.IO) {
                try { signedInClient(thread.forum()).upload(picture, "image/jpeg") } finally { Files.deleteIfExists(picture) }
            }
            message = message.appendBlock("![]($url)")
        } catch (e: Exception) {
            error = "Screenshot not uploaded: ${e.message}"
        } finally {
            busy = null
        }
    }
    val title = PbfPost.title(stepTitle, player, round)
    // the thread's other players, to @mention the next one (the forum notifies them)
    val mentions by produceState(emptyList<String>(), key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val client = ForumAccount.client(thread.forum()) ?: return@runCatching emptyList()
                val me = ForumAccount.player(thread.forum()).value?.userslug()
                client.newestPage(thread).posts().map { it.authorSlug() }.filter { it.isNotEmpty() && it != me }.distinct()
            }.getOrDefault(emptyList())
        }
    }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = "Uploading picture…"
            error = null
            try {
                val client = signedInClient(thread.forum())
                val url = withContext(Dispatchers.IO) {
                    val (file, mime) = copyPictureToCache(context, uri)
                    try { client.upload(file, mime) } finally { Files.deleteIfExists(file) }
                }
                message = message.appendBlock("![]($url)")
            } catch (e: Exception) {
                error = "Picture not uploaded: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun send() = scope.launch {
        busy = "Sending…"
        error = null
        try {
            val heldBack = withContext(Dispatchers.IO) {
                val gameName = GameController.gameData?.gameName ?: ""
                poster().send { url -> PbfPost.content(stepTitle, player, round, gameName, message.text, summary, url) }
            }
            onSent(heldBack)
        } catch (e: Exception) {
            error = "Not sent: ${e.message ?: e.javaClass.simpleName}. Your text and the save are kept; try again."
        } finally {
            busy = null
        }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                notices.forEach { InfoNote(it) }
                // the engine writes every event made while edit mode is on as "EDIT: ..."
                if (summary.contains("EDIT: ")) {
                    InfoNote("This turn contains changes made in edit mode: the EDIT lines below.")
                }
                Text(
                    summary.trim(),
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                ForumEditor(
                    value = message,
                    onValueChange = { message = it },
                    forum = thread.forum(),
                    label = "Comment (optional)",
                    enabled = busy == null,
                    onPicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    mentions = mentions,
                    modifier = Modifier.fillMaxWidth(),
                )
                busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    sendLater?.let { later ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    busy = "Saving…"
                                    runCatching { withContext(Dispatchers.IO) { later() } }
                                        .onSuccess { onSent(false) }
                                        .onFailure { e -> error = "Could not save: ${e.message}" }
                                    busy = null
                                }
                            },
                            enabled = busy == null,
                        ) { Text("Close and send later from the load screen") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    secondary(busy == null)
                    Button(onClick = { send() }, enabled = busy == null, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
fun DiceFailedDialog(request: DiceFailedRequest) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Dice server failed") },
        text = {
            Text(
                "${request.message.ifBlank { "No answer from dice.triplea-game.org." }}\n\n" +
                    "Forum games only roll on the dice server. Retry, or stop here: the game ends without posting.",
            )
        },
        confirmButton = { Button(onClick = { request.complete(true) }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { request.complete(false) }) { Text("Stop") } },
    )
}

/** The picked picture as a file the upload can read, with its type. */
internal fun copyPictureToCache(context: Context, uri: Uri): Pair<Path, String> {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val extension = mime.substringAfter('/', "jpg").let { if (it == "jpeg") "jpg" else it }
    val file = Files.createTempFile(context.cacheDir.toPath(), "upload-", ".$extension")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "The picture could not be opened." }
        Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    return file to mime
}
