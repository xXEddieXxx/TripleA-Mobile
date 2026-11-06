package org.triplea.mobile.app.forum

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.NodeBbClient

/**
 * Signs in on the forum's own login page (CAPTCHA and two factor included) and keeps the session
 * cookie once the forum says it signs someone in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ForumLoginScreen(forum: Forum, onSignedIn: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    /** The forum refuses the app's own requests (bot protection): signing in cannot finish. */
    var blocked by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to ${forum.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        blocked?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(
                    "$it Your forum login worked, but the app cannot read your games there. Play on this forum from a " +
                        "web browser or desktop TripleA for now.",
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        private var signedIn = false

                        override fun onPageFinished(view: WebView, url: String) {
                            val cookie = sessionCookie(forum) ?: return
                            scope.launch {
                                // a guest also has the cookie: only a signed-in session counts
                                val player = runCatching { ForumAccount.signIn(forum, cookie) }
                                    .onFailure { if (it is NodeBbClient.BlockedException) blocked = it.message }
                                    .getOrNull()
                                if (!signedIn && player != null) {
                                    signedIn = true
                                    onSignedIn()
                                }
                            }
                        }
                    }
                    loadUrl(forum.url + "login")
                }
            },
        )
        }
    }
}

private fun sessionCookie(forum: Forum): String? =
    CookieManager.getInstance().getCookie(forum.url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith(NodeBbClient.SESSION_COOKIE + "=") }
        ?.substringAfter('=')
