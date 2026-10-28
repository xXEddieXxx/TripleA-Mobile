package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.protocol.DefenderNotice

/** Time left as m:ss, or h:mm:ss from an hour on; never negative. */
internal fun countdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * "⚔ Battle decision · Minimap" over "Choose casualties · 0:52 · 1 of 2" with "Decide now": the notice with the least time
 * left of [notices] (sorted by the caller). It takes the status bar inset, so the screen below must not. The ticking clock
 * is local here, so only the banner recomposes every second.
 */
@Composable
internal fun DefenderBanner(notices: List<DefenderNotice>, onDecide: (gameId: String) -> Unit) {
    val first = notices.first()
    val now by produceState(System.currentTimeMillis() + Social.clockSkewMs) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis() + Social.clockSkewMs
        }
    }
    // the notice names no game; the first fetch tells, a failure leaves it out
    val name by produceState<String?>(null, first.gameId) {
        value = withContext(Dispatchers.IO) { runCatching { Social.api?.game(first.gameId)?.gameName }.getOrNull() }
    }
    val title = listOfNotNull("⚔ Battle decision", name).joinToString(" · ")
    val left = first.deadline?.let { countdown(it - now) }
    val count = "1 of ${notices.size}".takeIf { notices.size > 1 }
    val detail = listOfNotNull(DefenderNotice.label(first.kind), left, count).joinToString(" · ")
    val spoken = listOfNotNull(
        "Battle decision" + (name?.let { " in $it" } ?: ""),
        DefenderNotice.label(first.kind),
        left?.let { "$it left" },
        count,
    ).joinToString(", ")
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp).clearAndSetSemantics { contentDescription = spoken }) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { onDecide(first.gameId) }) { Text("Decide now") }
        }
    }
}
