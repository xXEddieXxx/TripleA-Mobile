package org.triplea.mobile.app.forum

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The game screen as a JPEG in the cache, to post with a question: the map and the battle window
 * as they are (pop-up dialogs are windows of their own and stay out). Null when it cannot be taken.
 */
suspend fun captureScreen(context: Context): Path? {
    val activity = context.activity() ?: return null
    val view = activity.window.decorView
    if (view.width == 0 || view.height == 0) return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val copied = suspendCancellableCoroutine { done ->
        PixelCopy.request(activity.window, bitmap, { result -> done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
    }
    if (!copied) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val file = Files.createTempFile(activity.cacheDir.toPath(), "screenshot-", ".jpg")
            Files.newOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file
        }.getOrNull()
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
