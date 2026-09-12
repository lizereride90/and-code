package com.yugahashimoto.andcode.feature.desktop

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class RfbDecoderTest {
    @Test
    fun `raw fills the rect in blue-green-red-pad byte order`() {
        val framebuffer = Framebuffer(2, 2)
        // 0xFF112233 -> blue=0x33, green=0x22, red=0x11, pad=0x00
        val input = streamOf(0x33, 0x22, 0x11, 0x00, 0x33, 0x22, 0x11, 0x00)
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 2, height = 1, encoding = ENCODING_RAW)
        assertEquals(argb(0x11, 0x22, 0x33), framebuffer.pixels[0])
        assertEquals(argb(0x11, 0x22, 0x33), framebuffer.pixels[1])
    }

    @Test
    fun `raw rects that fall off the framebuffer leave it untouched`() {
        val framebuffer = Framebuffer(1, 1)
        val input = streamOf(0x22, 0x33, 0x44, 0x00, 0x22, 0x33, 0x44, 0x00)
        RfbDecoders.decode(input, framebuffer, x = 1, y = 0, width = 2, height = 1, encoding = ENCODING_RAW)
        assertEquals(0, framebuffer.pixels[0])
    }

    @Test
    fun `copyRect copies the source rect to the target`() {
        val framebuffer = Framebuffer(4, 4)
        for (row in 0 until 2) {
            for (col in 0 until 2) {
                framebuffer.setPixel(col, row, argb(0x00, 0xAA, 0x00))
            }
        }
        val input = streamOf(0, 0, 0, 1) // sourceX=0, sourceY=1
        RfbDecoders.decode(input, framebuffer, x = 2, y = 2, width = 2, height = 1, encoding = ENCODING_COPY_RECT)
        assertEquals(argb(0x00, 0xAA, 0x00), framebuffer.pixels[2 * 4 + 2])
        assertEquals(argb(0x00, 0xAA, 0x00), framebuffer.pixels[2 * 4 + 3])
        // The source rect itself is untouched.
        assertEquals(argb(0x00, 0xAA, 0x00), framebuffer.pixels[1 * 4 + 0])
    }

    @Test
    fun `rre fills the background then applies each subrect`() {
        val framebuffer = Framebuffer(8, 8)
        val input =
            streamOf(
                // background red=0x11, green=0x11, blue=0x22
                0x22, 0x11, 0x11, 0x00,
                0, 0, 0, 2, // two subrects
                // subrect 1: red=0x33, green=0x44, blue=0x55 at (0,3), 2x2
                0x55, 0x44, 0x33, 0x00,
                0, 0,
                0, 3,
                0, 2, 0, 2,
                // subrect 2: red=0x77, green=0x88, blue=0x99 at (0,0), 1x1
                0x99, 0x88, 0x77, 0x00,
                0, 0,
                0, 0,
                0, 1, 0, 1,
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 8, height = 8, encoding = ENCODING_RRE)
        assertEquals(argb(0x11, 0x11, 0x22), framebuffer.pixels[7 * 8 + 7])
        assertEquals(argb(0x33, 0x44, 0x55), framebuffer.pixels[3 * 8 + 0])
        assertEquals(argb(0x33, 0x44, 0x55), framebuffer.pixels[4 * 8 + 1])
        assertEquals(argb(0x77, 0x88, 0x99), framebuffer.pixels[0])
    }

    @Test
    fun `hextile with background subencoding fills the tile`() {
        val framebuffer = Framebuffer(16, 16)
        val input =
            streamOf(
                0x04, // background-only tile
                0x20, 0x30, 0x50, 0x00, // background
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(argb(0x50, 0x30, 0x20), framebuffer.pixels[0])
        assertEquals(argb(0x50, 0x30, 0x20), framebuffer.pixels[15 * 16 + 15])
    }

    @Test
    fun `hextile raw tile stores the exact pixels`() {
        val framebuffer = Framebuffer(16, 16)
        val tile = buildList {
            repeat(16 * 16) { addAll(listOf(0xAA, 0xBB, 0xCC, 0x00)) }
        }
        val input = streamOf(0x01, *tile.toIntArray())
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(argb(0xCC, 0xBB, 0xAA), framebuffer.pixels[0])
        assertEquals(argb(0xCC, 0xBB, 0xAA), framebuffer.pixels[255])
    }

    @Test
    fun `hextile subrects are painted over the background`() {
        val framebuffer = Framebuffer(16, 16)
        val input =
            streamOf(
                0x04 or 0x40 or 0x80, // background + foreground + subrects
                0x10, 0x20, 0x30, 0x00, // background red=0x30, green=0x20, blue=0x10
                0xF0, 0xE0, 0xD0, 0x00, // foreground red=0xD0, green=0xE0, blue=0xF0
                2, // two subrects
                1, 1, 2, 2,
                5, 5, 1, 1,
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(argb(0xD0, 0xE0, 0xF0), framebuffer.pixels[1 * 16 + 1])
        assertEquals(argb(0xD0, 0xE0, 0xF0), framebuffer.pixels[2 * 16 + 2])
        assertEquals(argb(0xD0, 0xE0, 0xF0), framebuffer.pixels[5 * 16 + 5])
        assertEquals(argb(0x30, 0x20, 0x10), framebuffer.pixels[15 * 16 + 15])
    }

    private fun argb(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun streamOf(vararg bytes: Int): DataInputStream =
        DataInputStream(ByteArrayInputStream(bytes.map { it.toByte() }.toByteArray()))
}