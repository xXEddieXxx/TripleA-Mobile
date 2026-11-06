package org.triplea.mobile.app.forum

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.Http
import org.triplea.mobile.forum.NodeBbClient

/** Pictures of posts, decoded at most [MAX_SIDE] wide and kept while memory allows. */
object ForumPictures {
    private const val MAX_SIDE = 1440
    private val cache = object : LruCache<URI, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: URI, value: ImageBitmap) = value.width * value.height * 4
    }

    // shortcut: the whole file is read before decoding; fine for forum uploads, cap it if huge outside pictures appear
    suspend fun load(url: URI): ImageBitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            // the forum keeping it, signed in where possible; another site's picture goes without a session
            val forum = Forum.keeping(url).orElse(Forum.TRIPLEA)
            val client = ForumAccount.client(forum) ?: NodeBbClient(Http.real(false), forum, "")
            val bytes = client.picture(url)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { cache.put(url, it) }
        }.getOrNull()
    }
}

/** A post's picture, full width; nothing while it loads or when it cannot be loaded. */
@Composable
fun ForumPicture(url: URI, description: String? = null) {
    val picture by produceState<ImageBitmap?>(null, url) { value = ForumPictures.load(url) }
    picture?.let {
        Image(
            it,
            contentDescription = description,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}
