package org.triplea.mobile.app.forum

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
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
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
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
                                if (!signedIn && runCatching { ForumAccount.signIn(forum, cookie) }.getOrNull() != null) {
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

private fun sessionCookie(forum: Forum): String? =
    CookieManager.getInstance().getCookie(forum.url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith(NodeBbClient.SESSION_COOKIE + "=") }
        ?.substringAfter('=')
