package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app's dialog frame, used by every pop-up so they all look and behave the same: a plain
 * rounded sheet with a title, content that is bounded to the screen (a list inside scrolls by
 * itself), and a button row that always stays on screen, with room for a short status such as
 * "2 of 3" on its left. No icons, no decoration: the content is what the dialog shows.
 */
@Composable
fun AppDialog(
    title: String,
    /** Null when the dialog must be answered and cannot be dismissed by tapping outside. */
    onDismiss: (() -> kotlin.Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: String? = null,
    statusColor: Color? = null,
    buttons: @Composable RowScope.() -> kotlin.Unit,
    content: @Composable ColumnScope.() -> kotlin.Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 500.dp)
                .heightIn(max = screenHeight * 0.92f),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = if (onDismiss != null) 6.dp else 20.dp, top = if (onDismiss != null) 8.dp else 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (onDismiss != null) {
                        // the X closes without a choice; the buttons below confirm one
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "close") }
                    }
                }
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 20.dp)) { content() }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                }
            }
        }
    }
}

/** A tappable choice in a dialog list: a title and an optional second line, nothing else. */
@Composable
fun OptionRow(
    title: String,
    onClick: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> kotlin.Unit)? = null,
    trailing: (@Composable () -> kotlin.Unit)? = null,
    emphasized: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** A row with a leading slot (usually a unit icon), a title, a subtitle and a plus/minus stepper. */
@Composable
fun CountRow(
    title: String,
    subtitle: String?,
    value: Int,
    max: Int,
    onChange: (Int) -> kotlin.Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> kotlin.Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (value > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Stepper(value, max, onChange)
        }
    }
}

/** The confirming button of a dialog: a check. */
@Composable
fun ConfirmButton(enabled: Boolean = true, onClick: () -> kotlin.Unit) {
    FilledIconButton(enabled = enabled, onClick = onClick) {
        Icon(Icons.Filled.Check, contentDescription = "confirm")
    }
}

/** Minus, number, plus. */
@Composable
fun Stepper(value: Int, max: Int, onChange: (Int) -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > 0) {
            Icon(Icons.Filled.Remove, contentDescription = "less")
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < max) {
            Icon(Icons.Filled.Add, contentDescription = "more")
        }
    }
}

/** A short note inside a dialog or window: a sentence on a soft background. */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
