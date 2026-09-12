package com.yugahashimoto.andcode.feature.desktop

import java.io.DataInputStream

/**
 * The viewer's own copy of the received desktop, kept as 32-bit ARGB pixels so Compose can blit it
 * straight into a `Bitmap`. Written entirely by [RfbDecoders] as FramebufferUpdate rects arrive.
 */
class Framebuffer(
    val width: Int,
    val height: Int,
) {
    val pixels = IntArray(width * height)

    fun setPixel(
        x: Int,
        y: Int,
        argb: Int,
    ) {
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x] = argb
        }
    }

    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        argb: Int,
    ) {
        val endX = (x + width).coerceAtMost(this.width)
        val endY = (y + height).coerceAtMost(this.height)
        val startX = x.coerceAtLeast(0)
        val startY = y.coerceAtLeast(0)
        var row = startY
        while (row < endY) {
            var col = startX
            while (col < endX) {
                pixels[row * this.width + col] = argb
                col++
            }
            row++
        }
    }

    fun copyRect(
        sourceX: Int,
        sourceY: Int,
        targetX: Int,
        targetY: Int,
        span: Int,
        rows: Int,
    ) {
        val sourceMinX = sourceX.coerceAtLeast(0)
        val sourceMinY = sourceY.coerceAtLeast(0)
        val width = (targetX + span).coerceAtMost(this.width) - targetX
        val height = (targetY + rows).coerceAtMost(this.height) - targetY
        if (sourceMinX + width > this.width) return
        if (sourceMinY + height > this.height) return
        if (width <= 0 || height <= 0) return
        val copy = IntArray(width * height)
        var row = 0
        while (row < height) {
            val sourceOffset = (sourceMinY + row) * this.width + sourceMinX
            System.arraycopy(pixels, sourceOffset, copy, row * width, width)
            row++
        }
        var targetRow = 0
        while (targetRow < height) {
            val targetOffset = (targetY + targetRow) * this.width + targetX
            System.arraycopy(copy, targetRow * width, pixels, targetOffset, width)
            targetRow++
        }
    }
}

const val ENCODING_RAW = 0
const val ENCODING_COPY_RECT = 1
const val ENCODING_RRE = 2
const val ENCODING_HEXTILE = 5

/**
 * Decoders for the RFB framebuffer encodings the viewer requests: Raw, CopyRect, RRE and Hextile.
 *
 * Split from [RfbClient] so each decoding rule is a plain, testable function over a byte stream.
 * All pixel data arrives in the 32-bit true-colour little-endian format the client requests: four
 * bytes per pixel, blue, green, red, pad.
 */
object RfbDecoders {
    fun decode(
        input: DataInputStream,
        framebuffer: Framebuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        encoding: Int,
    ) {
        when (encoding) {
            ENCODING_RAW -> raw(input, framebuffer, x, y, width, height)
            ENCODING_COPY_RECT -> copyRect(input, framebuffer, x, y, width, height)
            ENCODING_RRE -> rre(input, framebuffer, x, y, width, height)
            ENCODING_HEXTILE -> hextile(input, framebuffer, x, y, width, height)
            else ->
                error("The desktop sent an unsupported encoding: $encoding")
        }
    }

    /** One 32-bit pixel in the wire order the viewer requests: blue, green, red, pad. */
    fun readPixel32(input: DataInputStream): Int {
        val blue = input.readUnsignedByte()
        val green = input.readUnsignedByte()
        val red = input.readUnsignedByte()
        input.readUnsignedByte()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun raw(
        input: DataInputStream,
        framebuffer: Framebuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        var row = 0
        while (row < height) {
            val targetY = y + row
            var col = 0
            while (col < width) {
                val argb = readPixel32(input)
                if (targetY in 0 until framebuffer.height) {
                    val targetX = x + col
                    if (targetX in 0 until framebuffer.width) {
                        framebuffer.pixels[targetY * framebuffer.width + targetX] = argb
                    }
                }
                col++
            }
            row++
        }
    }

    private fun copyRect(
        input: DataInputStream,
        framebuffer: Framebuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val sourceX = input.readUnsignedShort()
        val sourceY = input.readUnsignedShort()
        framebuffer.copyRect(sourceX, sourceY, x, y, width, height)
    }

    private fun rre(
        input: DataInputStream,
        framebuffer: Framebuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val background = readPixel32(input)
        val subrectCount = input.readInt()
        framebuffer.fillRect(x, y, width, height, background)
        var i = 0
        while (i < subrectCount) {
            val color = readPixel32(input)
            val subX = input.readUnsignedShort()
            val subY = input.readUnsignedShort()
            val subWidth = input.readUnsignedShort()
            val subHeight = input.readUnsignedShort()
            framebuffer.fillRect(x + subX, y + subY, subWidth, subHeight, color)
            i++
        }
    }

    private fun hextile(
        input: DataInputStream,
        framebuffer: Framebuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        var startY = 0
        while (startY < height) {
            val tileHeight = minOf(16, height - startY)
            val rectY = y + startY
            var startX = 0
            while (startX < width) {
                val tileWidth = minOf(16, width - startX)
                val rectX = x + startX
                val subencoding = input.readUnsignedByte()
                val isRaw = subencoding and 0x01 != 0
                val isBackground = subencoding and 0x04 != 0
                val isForeground = subencoding and 0x40 != 0
                val hasSubrects = subencoding and 0x80 != 0
                val colouredSubrects = subencoding and 0x02 != 0

                when {
                    isRaw -> raw(input, framebuffer, rectX, rectY, tileWidth, tileHeight)
                    else -> {
                        var background = 0
                        var foreground = 0
                        if (isBackground) background = readPixel32(input)
                        if (isForeground) foreground = readPixel32(input)
                        if (isBackground) {
                            framebuffer.fillRect(rectX, rectY, tileWidth, tileHeight, background)
                        }
                        if (hasSubrects) {
                            val subrectCount = input.readUnsignedByte()
                            var i = 0
                            while (i < subrectCount) {
                                var color = foreground
                                if (colouredSubrects) color = readPixel32(input)
                                val subX = input.readUnsignedByte()
                                val subY = input.readUnsignedByte()
                                val subWidth = input.readUnsignedByte()
                                val subHeight = input.readUnsignedByte()
                                framebuffer.fillRect(rectX + subX, rectY + subY, subWidth, subHeight, color)
                                i++
                            }
                        }
                    }
                }
                startX += 16
            }
            startY += 16
        }
    }
}