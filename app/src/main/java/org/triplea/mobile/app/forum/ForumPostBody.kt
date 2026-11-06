package org.triplea.mobile.app.forum

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.URI
import org.triplea.mobile.forum.NodeBbClient.ThreadPost

private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ALT = Regex("\\salt=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

/** Lines of a turn summary shown before "Show whole turn". */
private const val FOLDED_LINES = 15

/**
 * A post as the forum formats it: a TripleA turn summary as coloured lines (phases, battles,
 * losses, wins and dice set apart), any other post with its HTML formatting and links.
 */
@Composable
fun ForumPostBody(post: ThreadPost) {
    val text = post.text()
    if (text.startsWith("TripleA ")) {
        TurnSummary(text)
        post.pictures().forEach { ForumPicture(it) }
    } else {
        ForumHtml(post.html(), post.pictures())
    }
}

/** HTML text with its links, then its pictures (the text view cannot show them in line). */
@Composable
fun ForumHtml(html: String, pictures: List<URI>) {
    val linkColor = MaterialTheme.colorScheme.primary
    val styled = remember(html, linkColor) {
        AnnotatedString.fromHtml(
            // an <img> would show as a box: emoji by their alt text (the character), pictures below
            IMG.replace(html) { tag -> if ("emoji" in tag.value) ALT.find(tag.value)?.groupValues?.get(1).orEmpty() else "" },
            linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
        )
    }
    if (styled.isNotBlank()) Text(styled, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
    pictures.forEach { ForumPicture(it) }
}

@Composable
private fun TurnSummary(text: String) {
    val lines = remember(text) { text.lines().filter { it.isNotBlank() && it.trim() != "Savegame" } }
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val colors = summaryColors()
    val title = lines.firstOrNull().orEmpty()
    val body = lines.drop(1)
    val shown = if (expanded) body else body.take(FOLDED_LINES)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.title, fontWeight = FontWeight.Bold)
        Surface(
            // an inset on the post's card: the page colour, so the coloured lines keep their contrast
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                remember(shown, colors) { styledSummary(shown, colors) },
                Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (body.size > FOLDED_LINES) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show whole turn (${body.size - FOLDED_LINES} more lines)")
            }
        }
    }
}

private data class SummaryColors(
    val title: Color,
    val phase: Color,
    val battle: Color,
    val loss: Color,
    val win: Color,
    val dice: Color,
    val gain: Color,
)

@Composable
private fun summaryColors(): SummaryColors {
    val scheme = MaterialTheme.colorScheme
    // the app's own theme setting may differ from the system's
    val dark = scheme.surface.luminance() < 0.5f
    return SummaryColors(
        title = scheme.primary,
        phase = scheme.primary,
        battle = if (dark) Color(0xFFFFB74D) else Color(0xFFE65100),
        loss = scheme.error,
        win = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        dice = scheme.onSurfaceVariant,
        gain = scheme.secondary,
    )
}

/**
 * One colour per kind of summary line, as `HistoryLog` writes them; each line a paragraph indented
 * by its level (4 spaces each), so a wrapped line stays under its parent.
 */
private fun styledSummary(lines: List<String>, colors: SummaryColors): AnnotatedString = buildAnnotatedString {
    lines.forEach { line ->
        val trimmed = line.trim()
        val indent = (line.length - line.trimStart().length) / 4 * 14
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent.sp, restLine = (indent + 14).sp))) {
            appendSummaryLine(line, trimmed, colors)
        }
    }
}

private fun AnnotatedString.Builder.appendSummaryLine(line: String, trimmed: String, colors: SummaryColors) {
    val style = when {
        trimmed.startsWith("Round:") -> SpanStyle(color = colors.title, fontWeight = FontWeight.Bold)
        trimmed.startsWith("TripleA ") || trimmed == "Game History" -> SpanStyle(color = colors.dice)
        // a phase: "Purchase Units - Russians", indented once
        line.startsWith("    ") && !line.startsWith("        ") && trimmed.contains(" - ") ->
            SpanStyle(color = colors.phase, fontWeight = FontWeight.Bold)
        trimmed.startsWith("Battle in ") -> SpanStyle(color = colors.battle, fontWeight = FontWeight.Bold)
        trimmed.contains(" roll dice ") || trimmed.startsWith("Combat Hit Differential") ||
            trimmed.matches(Regex("\\w+ regular : .*")) -> SpanStyle(color = colors.dice)
        trimmed.startsWith("Casualties for ") || trimmed.contains(" lost") -> SpanStyle(color = colors.loss)
        trimmed.contains(" win") || trimmed.contains(" taking ") || trimmed.contains(" take ") ->
            SpanStyle(color = colors.win, fontWeight = FontWeight.SemiBold)
        trimmed.contains(" collect ") || trimmed.contains(" buy ") || trimmed.contains(" placed in ") ->
            SpanStyle(color = colors.gain)
        else -> null
    }
    if (style == null) append(trimmed) else withStyle(style) { append(trimmed) }
}
