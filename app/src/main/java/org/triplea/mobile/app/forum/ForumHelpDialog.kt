package org.triplea.mobile.app.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.triplea.mobile.app.ui.AppDialog

/** What Play by Forum is and how a game runs, section by section. */
private val HELP = listOf(
    "What it is" to
        "Play a game over a forum thread on forums.triplea-game.org. Every turn is a " +
        "post with a summary and the save game; the next player opens the save, plays and posts back. " +
        "Desktop TripleA players can play the same games.",
    "Sign in" to
        "Tap Sign in. You log in on the forum's own page; the app keeps only the forum's session, never your password.",
    "Your games" to
        "My games lists the games you started, posted in or follow. \"Your turn\" means someone else posted last; " +
        "tap the chip to show only those, again for the games waiting for an opponent. Show inactive adds games " +
        "without a post for 60 days. Browse lists the forum's open games to join. A game that is over, or that you " +
        "are out of: tap the bell in its thread to stop following it; it leaves My games and sends no notifications " +
        "(the Stopped chip lists those games, the bell follows again).",
    "Start a game" to
        "New game opens a thread and the game setup; \"Use an existing thread instead\" picks a thread that is already " +
        "there (search it; those you opened last come first). In a thread without a save, Start game sets up a game for " +
        "that thread. Choose the map, and enter your and the other players' dice emails (Add player for more than " +
        "two): every roll is made on dice.triplea-game.org and mailed to all of you. Emails you used before are " +
        "offered in each field. Register your email there once (the setup links to it).",
    "Play your turn" to
        "In the thread, tap Join: it opens the newest save to play on (Open save on a post also offers Watch, " +
            "to only look). The app keeps only the newest save of each forum game; a turn you put off sending stays. At the end of " +
        "your turn the app shows the summary: add a comment and pictures and tap Send. The turn and the save are " +
        "posted, and you are back in the thread. Keep playing goes on when the next nation is yours too; the summary " +
        "says when the next nation is an ally, and then one post covers all the nations you played.",
    "Ask the opponent" to
        "When the game asks something the opponent should decide (their losses, a scramble, a retreat or submerge), " +
        "tap Ask opponent. The question, a screenshot and the save are posted; stop until they answer, then open " +
        "the save again (Load game, or Open save on your post): it goes on at that question.",
    "Post the game any time" to
        "The game menu's \"Post save to forum\" posts the game as it stands, with the turn so far and your comment, " +
        "without ending the turn.",
    "In the thread" to
        "Write replies with the toolbar (bold, quote, list, link, pictures, @mention, a save game) and check them " +
        "with the preview. The ⋮ of a post quotes it, or edits or deletes your own. Unsent text stays as a draft. " +
        "Pull down to refresh; new posts since your last visit are marked.",
    "Notifications" to
        "While you are signed in, the app looks for your turns every 30 minutes and notifies you once per new post. " +
        "Tap the notification to open the thread.",
)

@Composable
fun ForumHelpDialog(onDismiss: () -> Unit) {
    AppDialog(
        title = "How Play by Forum works",
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss) { Text("Got it") } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HELP.forEach { (heading, text) ->
                Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
