package com.ninepointnine.desktop.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.ninepointnine.desktop.model.AppEntry

class IconCache(
    private val context: Context,
) {
    private val sizePx = context.resources.getDimensionPixelSize(com.ninepointnine.desktop.R.dimen.drawer_app_icon_size)
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(entry: AppEntry): Bitmap? {
        cache.get(entry.iconKey)?.let { return it }
        val drawable = try {
            context.packageManager.getApplicationIcon(entry.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return drawable.toBitmap(sizePx).also { cache.put(entry.iconKey, it) }
    }

    fun invalidatePackage(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName:") }.forEach(cache::remove)
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            cache.evictAll()
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.size() / 2)
        }
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(bounds)
        setBounds(0, 0, size, size)
        draw(Canvas(bitmap))
        bounds = previousBounds
        return bitmap
    }

    private companion object {
        const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    }
}
