package org.triplea.mobile.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The map's game notes as HTML, like the desktop Help > Game Notes: relative `<img>` sources load
 * from [baseUrl] (the map's doc/images folder), links open in the browser, JavaScript stays off.
 * Tapping an image shows it alone, full screen, to pinch-zoom; a tap or back closes it.
 */
@Composable
fun GameNotesView(html: String, baseUrl: String?, modifier: Modifier = Modifier) {
    var zoomed by remember { mutableStateOf<String?>(null) }
    HtmlView(html, baseUrl, modifier) { zoomed = it }
    zoomed?.let { src ->
        Dialog(onDismissRequest = { zoomed = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            HtmlView("<img src=\"${src.replace("\"", "%22")}\">", baseUrl, Modifier.fillMaxSize()) { zoomed = null }
        }
    }
}

@SuppressLint("ClickableViewAccessibility") // the tap listener only peeks, the WebView still gets every event
@Composable
private fun HtmlView(html: String, baseUrl: String?, modifier: Modifier, onImageTap: (String) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.allowFileAccess = true // the notes images are files in the map folder
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    // in-page anchors and map files stay here, web links go to the browser, anything else is blocked
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme
                        if (scheme == "https" || scheme == "http") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        }
                        return scheme != "file"
                    }
                }
                val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val hit = hitTestResult
                        if (hit.type == WebView.HitTestResult.IMAGE_TYPE) hit.extra?.let(onImageTap)
                        return false
                    }
                })
                setOnTouchListener { _, event -> taps.onTouchEvent(event); false }
                // manual pages are often wider than a phone: fit them to the width, pinch to zoom in
                loadDataWithBaseURL(baseUrl, "<style>img{max-width:100%;height:auto}</style>$html", "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() }, // frees the decoded manual images
    )
}
