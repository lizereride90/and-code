package com.yugahashimoto.andcode.data.vnc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Backing store for the remote framebuffer. Written by the RFB decoding thread and drawn by the
 * UI thread; every mutating call is synchronized so the [Canvas.drawBitmap] in [draw] never sees a
 * partially updated bitmap.
 *
 * Pixels are stored directly in the [Bitmap] via [Bitmap.setPixels], which also avoids a second
 * copy and an IntArray↔Bitmap conversion per update.
 */
class Framebuffer(width: Int, height: Int) {
    @Volatile
    var width: Int = width
        private set

    @Volatile
    var height: Int = height
        private set

    private var lock = Object()

    private var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Invoked after pixels change so the owning view can schedule a redraw. */
    var dirtyListener: ((x: Int, y: Int, w: Int, h: Int) -> Unit)? = null

    /** Invoked after the framebuffer size changes so the owning view can re-fit itself. */
    var onSizeChanged: ((newWidth: Int, newHeight: Int) -> Unit)? = null

    fun putRowPixels(
        x: Int,
        y: Int,
        rectW: Int,
        rectH: Int,
        argb: IntArray,
    ) {
        synchronized(lock) {
            bitmap.setPixels(argb, 0, rectW, x, y, rectW, rectH)
        }
        dirtyListener?.invoke(x, y, rectW, rectH)
    }

    fun copyRect(
        dx: Int,
        dy: Int,
        w: Int,
        h: Int,
        srcX: Int,
        srcY: Int,
    ) {
        synchronized(lock) {
            if (w <= 0 || h <= 0) return
            val src = IntArray(w * h)
            bitmap.getPixels(src, 0, w, srcX, srcY, w, h)
            bitmap.setPixels(src, 0, w, dx, dy, w, h)
        }
        dirtyListener?.invoke(dx, dy, w, h)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        if (newWidth == width && newHeight == height) return
        val resized = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        synchronized(lock) {
            Canvas(resized).drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            bitmap = resized
            width = newWidth
            height = newHeight
        }
        onSizeChanged?.invoke(newWidth, newHeight)
        dirtyListener?.invoke(0, 0, newWidth, newHeight)
    }

    /** Draws the framebuffer onto [canvas] using the current transform. Must be called on the UI thread. */
    fun draw(
        canvas: Canvas,
        transform: android.graphics.Matrix,
        paint: Paint,
    ) {
        synchronized(lock) {
            canvas.drawBitmap(bitmap, transform, paint)
        }
    }
}