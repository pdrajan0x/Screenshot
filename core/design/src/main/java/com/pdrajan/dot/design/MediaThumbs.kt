package com.pdrajan.dot.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer

/**
 * A grid thumbnail of a MediaStore image, sharp at the cell's real size. The first time, the
 * picture is decoded at exactly that size (Android's own cached thumbnails are too small and
 * look blurry); the result is kept in the image disk cache, so after that a cell is a quick small
 * WebP instead of a full-size screenshot decoded while scrolling. Register [MediaThumbs.addTo]
 * on the app's image loader.
 */
data class MediaThumb(val uri: Uri)

object MediaThumbs {
    /** The memory-cache key of [id]'s grid thumbnail: the viewer shows it while the full image loads. */
    fun cacheKey(id: Long) = "thumb-$id"

    fun addTo(builder: coil3.ComponentRegistry.Builder, context: Context) {
        builder.add(Keyer<MediaThumb> { data, _ -> "thumb:${data.uri}" }, MediaThumb::class)
        // Older Android: decode the image itself, as Coil does for any picture.
        builder.add(Mapper<MediaThumb, Uri> { data, _ -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) null else data.uri }, MediaThumb::class)
        builder.add(ThumbFetcher.Factory(context.applicationContext), MediaThumb::class)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class ThumbFetcher(
        private val context: Context,
        private val data: MediaThumb,
        private val options: Options,
        private val diskCache: DiskCache?,
    ) : Fetcher {
        override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
            // Sizes rounded up to 64 px, so a few column counts share cached thumbnails.
            val w = bucket(options.size.width.pxOrElse { DEFAULT })
            val h = bucket(options.size.height.pxOrElse { w })
            val key = "thumb:${data.uri}:${w}x$h"
            diskCache?.openSnapshot(key)?.let { snapshot ->
                return@withContext SourceFetchResult(
                    ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
            val bitmap = decode(w, h)
            diskCache?.openEditor(key)?.let { editor ->
                runCatching {
                    diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
                        bitmap.compress(format, 90, sink.outputStream())
                    }
                    editor.commit()
                }.onFailure { runCatching { editor.abort() } }
            }
            ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }

        /** The picture scaled so it covers [w] x [h] (cells crop it), never scaled up. */
        private fun decode(w: Int, h: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, data.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = maxOf(w.toFloat() / info.size.width, h.toFloat() / info.size.height).coerceAtMost(1f)
                if (scale < 1f) {
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }

        class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
            override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ThumbFetcher(context, data, options, imageLoader.diskCache) else null
        }
    }

    private fun bucket(px: Int) = ((px.coerceAtLeast(1) + 63) / 64) * 64

    private val format: Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

    private const val DEFAULT = 384
}
