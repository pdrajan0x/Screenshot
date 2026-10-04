package com.pdrajan.dot.design

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A grid thumbnail of a MediaStore image. Android keeps small cached thumbnails of every photo,
 * so a grid cell is a quick small JPEG instead of a full-size PNG screenshot decoded and scaled
 * down while scrolling. Register [MediaThumbs.addTo] on the app's image loader.
 */
data class MediaThumb(val uri: Uri)

object MediaThumbs {
    /** The memory-cache key of [id]'s grid thumbnail: the viewer shows it while the full image loads. */
    fun cacheKey(id: Long) = "thumb-$id"

    fun addTo(builder: coil3.ComponentRegistry.Builder, context: Context) {
        builder.add(Keyer<MediaThumb> { data, _ -> "thumb:${data.uri}" }, MediaThumb::class)
        // Older Android has no thumbnail API: decode the image itself, as before.
        builder.add(Mapper<MediaThumb, Uri> { data, _ -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else data.uri }, MediaThumb::class)
        builder.add(ThumbFetcher.Factory(context.applicationContext), MediaThumb::class)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class ThumbFetcher(private val context: Context, private val data: MediaThumb, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
            val w = options.size.width.pxOrElse { DEFAULT }.coerceAtLeast(1)
            val h = options.size.height.pxOrElse { w * 16 / 9 }.coerceAtLeast(1)
            val bitmap = runCatching { context.contentResolver.loadThumbnail(data.uri, Size(w, h), null) }.getOrElse {
                // No cached thumbnail (some phones, odd formats): decode the image at that size.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, data.uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = maxOf(w.toFloat() / info.size.width, h.toFloat() / info.size.height).coerceAtMost(1f)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
            ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }

        class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
            override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbFetcher(context, data, options) else null
        }
    }

    private const val DEFAULT = 360
}
