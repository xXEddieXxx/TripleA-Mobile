package org.triplea.mobile.app.forum

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.ForumMarkdown
import org.triplea.mobile.forum.NodeBbClient
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The forum's Markdown editor: the text or its preview, and a toolbar for preview, @mention (of
 * [mentions], userslugs), bold, italic, quote, list, link, a picture and (when given) a save game;
 * [trailing] sits at the toolbar's end, eg a send button.
 */
@Composable
fun ForumEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    forum: Forum,
    label: String,
    enabled: Boolean,
    onPicture: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveGame: (() -> Unit)? = null,
    mentions: List<String> = emptyList(),
    trailing: @Composable () -> Unit = {},
) {
    var preview by remember { mutableStateOf(false) }
    var mentionMenu by remember { mutableStateOf(false) }
    Column(modifier) {
        if (preview) {
            val html = remember(value.text) { ForumMarkdown.toHtml(value.text) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (value.text.isBlank()) Text("Nothing written yet.", Modifier.padding(top = 6.dp))
                    else ForumHtml(html, remember(html, forum) { NodeBbClient.picturesIn(html, forum.uri()) })
                }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(label) },
                minLines = 2,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Tool(if (preview) Icons.Default.Edit else Icons.Default.Visibility, if (preview) "edit" else "preview") { preview = !preview }
                if (mentions.isNotEmpty()) {
                    Box {
                        Tool(Icons.Default.AlternateEmail, "mention a player", !preview) { mentionMenu = true }
                        DropdownMenu(expanded = mentionMenu, onDismissRequest = { mentionMenu = false }) {
                            mentions.forEach { slug ->
                                DropdownMenuItem(text = { Text("@$slug") }, onClick = {
                                    mentionMenu = false
                                    onValueChange(value.insert("@$slug "))
                                })
                            }
                        }
                    }
                }
                Tool(Icons.Default.FormatBold, "bold", !preview) { onValueChange(value.wrap("**", "**", "bold")) }
                Tool(Icons.Default.FormatItalic, "italic", !preview) { onValueChange(value.wrap("*", "*", "italic")) }
                Tool(Icons.Default.FormatQuote, "quote", !preview) { onValueChange(value.prefixLines("> ")) }
                Tool(Icons.AutoMirrored.Filled.FormatListBulleted, "list", !preview) { onValueChange(value.prefixLines("- ")) }
                Tool(Icons.Default.Link, "link", !preview) { onValueChange(value.wrap("[", "](https://)", "text")) }
                Tool(Icons.Default.AddPhotoAlternate, "add picture", enabled, onPicture)
                onSaveGame?.let { Tool(Icons.Default.UploadFile, "attach save game", enabled, it) }
            }
            trailing()
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = description) }
}

/** The selection (or [placeholder]) between [before] and [after], left selected to type over. */
internal fun TextFieldValue.wrap(before: String, after: String, placeholder: String): TextFieldValue {
    val inner = text.substring(selection.min, selection.max).ifEmpty { placeholder }
    val start = selection.min + before.length
    return TextFieldValue(text.replaceRange(selection.min, selection.max, before + inner + after), TextRange(start, start + inner.length))
}

/** [prefix] before every line the selection touches; the cursor after them. */
internal fun TextFieldValue.prefixLines(prefix: String): TextFieldValue {
    val lineStart = text.lastIndexOf('\n', selection.min - 1) + 1
    val block = text.substring(lineStart, selection.max).lines().joinToString("\n") { prefix + it }
    return TextFieldValue(text.replaceRange(lineStart, selection.max, block), TextRange(lineStart + block.length))
}

/** [insert] in place of the selection, the cursor after it. */
internal fun TextFieldValue.insert(insert: String): TextFieldValue {
    val end = selection.min + insert.length
    return TextFieldValue(text.replaceRange(selection.min, selection.max, insert), TextRange(end))
}

/** [markdown] as its own paragraph at the end, eg an uploaded picture or save. */
internal fun TextFieldValue.appendBlock(markdown: String): TextFieldValue {
    val result = text.trimEnd() + (if (text.isBlank()) "" else "\n\n") + markdown
    return TextFieldValue(result, TextRange(result.length))
}
