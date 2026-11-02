package app.simplestcut.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kleiner Speicher-Cache, damit beim Scrollen nicht jedes Vorschaubild neu geladen wird (~32 MB). */
private val thumbnailCache = object : LruCache<Uri, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount
}

@Composable
fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf(thumbnailCache.get(uri)) }

    LaunchedEffect(uri) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(320, 320), null) }
                .getOrNull()
                ?.also { thumbnailCache.put(uri, it) }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
