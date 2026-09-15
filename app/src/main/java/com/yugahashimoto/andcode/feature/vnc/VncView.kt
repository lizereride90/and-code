package com.yugahashimoto.andcode.feature.vnc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.yugahashimoto.andcode.data.vnc.Framebuffer
import com.yugahashimoto.andcode.data.vnc.VncScaling
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the VNC framebuffer with pan/zoom and turns touch gestures into remote pointer events.
 *
 * Gesture model:
 *  - One finger down + move drags the remote mouse (left button held).
 *  - A quick tap is a left click; a long press (or a tap with "right-click mode" on) is a right click.
 *  - Pinch zooms; two-finger dragging pans the local view when [panMode] is enabled.
 *  - Double-tap toggles between fit-to-screen and 100%.
 */
class VncView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        var framebuffer: Framebuffer? = null
            set(value) {
                field = value
                value?.dirtyListener = { _, _, _, _ -> postInvalidateOnAnimation() }
                value?.onSizeChanged = { _, _ -> resetViewport() }
                resetViewport()
            }

        var scaling: String = VncScaling.FIT
            set(value) {
                field = value
                resetViewport()
            }

        var panMode: Boolean = false
        var rightClickMode: Boolean = false

        /** Reports remote pointer events in framebuffer coordinates. Called on the UI thread. */
        var pointerListener: ((buttonMask: Int, x: Int, y: Int) -> Unit)? = null

        private val paint = Paint().apply { isFilterBitmap = false }
        private val transform = Matrix()
        private var viewW = 0
        private var viewH = 0

        private var scaleX: Float = 1f
        private var scaleY: Float = 1f
        private var offsetX: Float = 0f
        private var offsetY: Float = 0f

        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var movedBeyondSlop = false
        private var longPressed = false
        private var wasPinching = false
        private var pointerDown = false
        private var lastTapTime = 0L

        private val handler = Handler(Looper.getMainLooper())
        private val longPressRunnable =
            Runnable {
                if (pointerDown && !movedBeyondSlop && !wasPinching) {
                    longPressed = true
                    sendPointer(buttonMask = 2)
                    sendPointer(buttonMask = 0)
                    handler.post {
                        // A tap after the long-press must not also fire a click.
                        lastTapTime = System.currentTimeMillis()
                    }
                }
            }

        private val scaleDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        wasPinching = true
                        pointerDown = false
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        applyZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        wasPinching = false
                    }
                },
            )

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewW = w
            viewH = h
            resetViewport()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fb = framebuffer ?: return
            if (fb.width <= 0 || fb.height <= 0) return
            fb.draw(canvas, transform, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (framebuffer == null) return super.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    movedBeyondSlop = false
                    longPressed = false
                    wasPinching = false
                    pointerDown = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    if (isDoubleTap()) {
                        toggleZoom()
                        // Suppresses the click that the quick UP of this tap would otherwise send.
                        longPressed = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (scaleDetector.isInProgress) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (!movedBeyondSlop) {
                            val fingerDx = event.x - downX
                            val fingerDy = event.y - downY
                            movedBeyondSlop = (fingerDx * fingerDx + fingerDy * fingerDy) > TOUCH_SLOP_SQUARED
                        }
                        if (panMode) {
                            offsetX += dx
                            offsetY += dy
                            updateTransform()
                            invalidate()
                        } else if (movedBeyondSlop && pointerDown) {
                            sendPointer(buttonMask = 1, x = fbX(event.x), y = fbY(event.y))
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    } else if (event.pointerCount >= 2 && panMode) {
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        offsetX += midX - lastTwoFingerX
                        offsetY += midY - lastTwoFingerY
                        lastTwoFingerX = midX
                        lastTwoFingerY = midY
                        updateTransform()
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    handler.removeCallbacks(longPressRunnable)
                    pointerDown = false
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!scaleDetector.isInProgress && event.pointerCount <= 1) {
                        val fingerDx = event.x - downX
                        val fingerDy = event.y - downY
                        val quick =
                            (System.currentTimeMillis() - downTime) < TAP_TIMEOUT_MS &&
                                (fingerDx * fingerDx + fingerDy * fingerDy) <= TOUCH_SLOP_SQUARED
                        if (quick && !longPressed && !isDoubleTap()) {
                            // Click.
                            val mask = if (rightClickMode) 2 else 1
                            sendPointer(buttonMask = mask, x = fbX(event.x), y = fbY(event.y))
                            sendPointer(buttonMask = 0, x = fbX(event.x), y = fbY(event.y))
                            lastTapTime = System.currentTimeMillis()
                        } else if (pointerDown && !panMode) {
                            sendPointer(buttonMask = 0, x = fbX(event.x), y = fbY(event.y))
                        }
                    }
                    pointerDown = false
                    wasPinching = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    pointerDown = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private var lastTwoFingerX = 0f
        private var lastTwoFingerY = 0f

        private fun isDoubleTap(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < DOUBLE_TAP_TIMEOUT_MS) {
                lastTapTime = 0L
                return true
            }
            return false
        }

        private fun toggleZoom() {
            val fb = framebuffer ?: return
            val fitScale = min(viewW.toFloat() / fb.width, viewH.toFloat() / fb.height)
            val centerX = viewW / 2f
            val centerY = viewH / 2f
            if (scaleX <= fitScale * 1.01f) {
                applyZoom(fitScale / max(scaleX, 0.0001f) * 1.5f, centerX, centerY)
            } else {
                resetViewport()
            }
        }

        private fun resetViewport() {
            val fb = framebuffer ?: return
            if (viewW == 0 || viewH == 0 || fb.width <= 0 || fb.height <= 0) return
            when (scaling) {
                VncScaling.STRETCH -> {
                    scaleX = viewW.toFloat() / fb.width
                    scaleY = viewH.toFloat() / fb.height
                    offsetX = 0f
                    offsetY = 0f
                }

                VncScaling.ONE -> {
                    scaleX = 1f
                    scaleY = 1f
                    offsetX = 0f
                    offsetY = 0f
                }

                else -> {
                    scaleX = min(viewW.toFloat() / fb.width, viewH.toFloat() / fb.height)
                    scaleY = scaleX
                    offsetX = (viewW - fb.width * scaleX) / 2f
                    offsetY = (viewH - fb.height * scaleY) / 2f
                }
            }
            updateTransform()
            invalidate()
        }

        private fun updateTransform() {
            transform.reset()
            transform.postScale(scaleX, scaleY)
            transform.postTranslate(offsetX, offsetY)
        }

        private fun applyZoom(
            gestureScale: Float,
            focalX: Float,
            focalY: Float,
        ) {
            val fbXBefore = (focalX - offsetX) / scaleX
            val fbYBefore = (focalY - offsetY) / scaleY
            scaleX = (scaleX * gestureScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
            scaleY = (scaleY * gestureScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offsetX = focalX - fbXBefore * scaleX
            offsetY = focalY - fbYBefore * scaleY
            updateTransform()
            invalidate()
        }

        private fun sendPointer(
            buttonMask: Int,
            x: Int = fbX(lastTouchX),
            y: Int = fbY(lastTouchY),
        ) {
            pointerListener?.invoke(buttonMask, x, y)
        }

        private fun fbX(viewX: Float): Int {
            val fb = framebuffer ?: return 0
            return (((viewX - offsetX) / scaleX).toInt()).coerceIn(0, fb.width - 1)
        }

        private fun fbY(viewY: Float): Int {
            val fb = framebuffer ?: return 0
            return (((viewY - offsetY) / scaleY).toInt()).coerceIn(0, fb.height - 1)
        }

        private companion object {
            const val LONG_PRESS_MS = 550L
            const val TAP_TIMEOUT_MS = 350L
            const val DOUBLE_TAP_TIMEOUT_MS = 300L
            const val MAX_ZOOM = 8f
            const val MIN_ZOOM = 0.05f
            const val TOUCH_SLOP = 12f
            const val TOUCH_SLOP_SQUARED = TOUCH_SLOP * TOUCH_SLOP
        }
    }
