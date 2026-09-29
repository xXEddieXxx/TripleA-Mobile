package org.triplea.mobile.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

const val SOURCE_URL = "https://github.com/xXEddieXxx/TripleA-Mobile"
private const val UPSTREAM_URL = "https://github.com/triplea-game/triplea"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

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

/** A third party component and the license it is distributed under. */
private class Component(val name: String, val license: String, val url: String)

private val COMPONENTS = listOf(
    Component("TripleA game engine, unit images, flags and sounds", "GPL-3.0", UPSTREAM_URL),
    Component("Android Jetpack (Compose, Activity, Lifecycle, Navigation, Core)", "Apache-2.0", "https://developer.android.com/jetpack"),
    Component("Kotlin and kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Component("Guava", "Apache-2.0", "https://github.com/google/guava"),
    Component("Gson", "Apache-2.0", "https://github.com/google/gson"),
    Component("Apache Commons IO, Lang, Text, Math", "Apache-2.0", "https://commons.apache.org"),
    Component("Woodstox and StAX2 API", "Apache-2.0 / BSD-2-Clause", "https://github.com/FasterXML/woodstox"),
    Component("SnakeYAML Engine", "Apache-2.0", "https://bitbucket.org/snakeyaml/snakeyaml-engine"),
    Component("SLF4J", "MIT", "https://www.slf4j.org"),
    Component("Project Lombok (build time only)", "MIT", "https://projectlombok.org"),
    Component("Jakarta XML Binding API", "BSD-3-Clause (EDL 1.0)", "https://github.com/jakartaee/jaxb-api"),
    Component("JSR-305 annotations", "BSD-3-Clause", "https://github.com/findbugsproject/findbugs"),
    Component("JetBrains annotations", "Apache-2.0", "https://github.com/JetBrains/java-annotations"),
    Component("desugar_jdk_libs", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs"),
)

/** About page: version, license, source code, and the licenses of everything the app ships. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    var showComponents by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("TripleA Mobile $version", style = MaterialTheme.typography.headlineSmall)
            Text(
                "An unofficial Android port of TripleA. Not made by or affiliated with the TripleA project.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Section("Links")
            LinkRow("Report a bug", "$SOURCE_URL/issues") { openBugReport(context) }
            LinkRow("Source code", SOURCE_URL) { open(SOURCE_URL) }
            LinkRow("TripleA (upstream project)", UPSTREAM_URL) { open(UPSTREAM_URL) }

            Section("License")
            Text(
                "Free software under the GNU General Public License v3, without any warranty. Based on the TripleA " +
                    "game engine (GPL-3.0, © the TripleA developers), modified for Android; the complete source " +
                    "code including those modifications is at the link above. Maps are made by the TripleA " +
                    "community and belong to their authors.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinkRow("GNU General Public License v3", GPL_URL) { open(GPL_URL) }

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Third-party components", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { showComponents = !showComponents }) { Text(if (showComponents) "Hide" else "Show") }
            }
            HorizontalDivider(Modifier.padding(bottom = 6.dp))
            if (showComponents) {
                COMPONENTS.forEach { component -> LinkRow(component.name, component.license) { open(component.url) } }
                Text(
                    "Apache-2.0, MIT and BSD components are provided \"AS IS\", without warranty of any kind; " +
                        "each project's page carries its license and copyright notice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
    HorizontalDivider(Modifier.padding(bottom = 6.dp))
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
    }
}
