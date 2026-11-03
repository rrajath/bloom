package com.rrajath.grove.attach

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade

/**
 * The app-wide Coil [ImageLoader] for attachments. Images come from SAF
 * content URIs in the vault, never the network, so there's no network
 * fetcher and no disk cache (Coil only disk-caches network results; decoding
 * a local file again is the cheap path). Decoded bitmaps stay in a memory
 * cache capped at 15% of the app's heap budget.
 *
 * GIFs: the platform's animated decoder on API 28+, Coil's Movie-based one
 * below. Inline images ask for the first frame per request (read mode); the
 * viewer lets them animate.
 */
object AttachmentImageLoader {
    private const val MEMORY_CACHE_PERCENT = 0.15

    fun build(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
            .diskCache(null)
            .crossfade(true)
            .build()
}
