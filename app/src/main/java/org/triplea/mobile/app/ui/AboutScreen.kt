package org.triplea.mobile.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.triplea.mobile.app.R

const val SOURCE_URL = "https://github.com/xXEddieXxx/TripleA-Mobile"
private const val UPSTREAM_URL = "https://github.com/triplea-game/triplea"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

private val TESTERS = listOf("Locke", "Gammer1", "MinotaurLP", "Karl582003")

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** Opens GitHub's new-issue page in the browser with version, device and map already filled in. */
fun openBugReport(context: Context, map: String? = null) {
    val body = """
        **Version:** ${appVersion(context)}
        **Device:** ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}
        **Map:** ${map.orEmpty()}

        **Steps:**
        1.

        **Expected:**

        **Actual:**
    """.trimIndent()
    val url = "$SOURCE_URL/issues/new".toUri().buildUpon().appendQueryParameter("body", body).build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
}

/** About page: version, links, credits and the license; the libraries used are listed in the repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // one readable column, also on tablets and in landscape
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 24.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.triplea_logo), contentDescription = null, modifier = Modifier.height(112.dp))
                    Text("TripleA Mobile", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
                    Pill("Version $version", Modifier.padding(top = 8.dp))
                }

                Group("Links") {
                    AboutRow(Icons.Filled.BugReport, "Report a bug", "Opens a new issue on GitHub", onClick = { openBugReport(context) })
                    GroupDivider()
                    AboutRow(Icons.Filled.Code, "Source code", SOURCE_URL.removePrefix("https://"), onClick = { open(SOURCE_URL) })
                }

                Group("Credits") {
                    AboutRow(
                        Icons.Filled.Public,
                        "TripleA",
                        "The original game, engine, unit images, flags and sounds, by the TripleA developers and community",
                        onClick = { open(UPSTREAM_URL) },
                    )
                    GroupDivider()
                    AboutRow(Icons.Filled.Person, "Eddie", "Android port")
                    GroupDivider()
                    AboutRow(Icons.Filled.Groups, "Testers") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) { TESTERS.forEach { Pill(it) } }
                    }
                }

                Group("License") {
                    Text(
                        "Free software under the GNU General Public License v3, without any warranty. Based on the TripleA " +
                            "game engine (GPL-3.0, © the TripleA developers), modified for Android; the complete source " +
                            "code including those modifications is at the source code link above. Maps are made by the " +
                            "TripleA community and belong to their authors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()
                    AboutRow(Icons.Filled.Gavel, "GNU General Public License v3", "gnu.org", onClick = { open(GPL_URL) })
                }
            }
        }
    }
}

/** A titled block of rows on a rounded surface. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

/** Separates rows of a [Group]; inset to line up with the row text. */
@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp))
}

/** A row with an icon, a title and a second line; with [onClick] it opens a page in the browser. */
@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Row(
        (if (onClick != null) Modifier.clickable(onClickLabel = "open in the browser", role = Role.Button, onClick = onClick) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            extra()
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
            )
        }
    }
}

/** A small rounded label in the theme's primary color. */
@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
