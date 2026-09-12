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
        assertEquals(0xFF112233, framebuffer.pixels[0])
        assertEquals(0xFF112233, framebuffer.pixels[1])
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
                framebuffer.setPixel(col, row, 0xFF00AA00)
            }
        }
        val input = streamOf(0, 1) // sourceX=0, sourceY=1
        RfbDecoders.decode(input, framebuffer, x = 2, y = 2, width = 2, height = 1, encoding = ENCODING_COPY_RECT)
        assertEquals(0xFF00AA00, framebuffer.pixels[2 * 4 + 2])
        assertEquals(0xFF00AA00, framebuffer.pixels[2 * 4 + 3])
        // The source rect itself is untouched.
        assertEquals(0xFF00AA00, framebuffer.pixels[1 * 4 + 0])
    }

    @Test
    fun `rre fills the background then applies each subrect`() {
        val framebuffer = Framebuffer(8, 8)
        val input =
            streamOf(
                // background 0xFF111122
                0x22, 0x11, 0x11, 0x00,
                0, 0, 0, 2, // two subrects
                // subrect 1: 0xFF334455 at (0,3), 2x2 (each field is a 2-byte short)
                0x55, 0x44, 0x33, 0x00,
                0, 0,
                0, 3,
                0, 2, 0, 2,
                // subrect 2: 0xFF778899 at (0,0), 1x1
                0x99, 0x88, 0x77, 0x00,
                0, 0,
                0, 0,
                0, 1, 0, 1,
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 8, height = 8, encoding = ENCODING_RRE)
        assertEquals(0xFF111122, framebuffer.pixels[7 * 8 + 7])
        assertEquals(0xFF334455, framebuffer.pixels[3 * 8 + 0])
        assertEquals(0xFF334455, framebuffer.pixels[4 * 8 + 1])
        assertEquals(0xFF778899, framebuffer.pixels[0])
    }

    @Test
    fun `hextile with background subencoding fills the tile`() {
        val framebuffer = Framebuffer(16, 16)
        val input =
            streamOf(
                0x04, // background-only tile
                0x20, 0x30, 0x50, 0x00, // background 0xFF503020
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(0xFF503020, framebuffer.pixels[0])
        assertEquals(0xFF503020, framebuffer.pixels[15 * 16 + 15])
    }

    @Test
    fun `hextile raw tile stores the exact pixels`() {
        val framebuffer = Framebuffer(16, 16)
        val tile = buildList {
            repeat(16 * 16) { addAll(listOf(0xAA, 0xBB, 0xCC, 0x00)) }
        }
        val input = streamOf(0x01, *tile.toIntArray())
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(0xFFCCBBAA, framebuffer.pixels[0])
        assertEquals(0xFFCCBBAA, framebuffer.pixels[255])
    }

    @Test
    fun `hextile subrects are painted over the background`() {
        val framebuffer = Framebuffer(16, 16)
        val input =
            streamOf(
                0x04 or 0x40 or 0x80, // background + foreground + subrects
                0x10, 0x20, 0x30, 0x00, // background 0xFF302010
                0xF0, 0xE0, 0xD0, 0x00, // foreground 0xFFD0E0F0
                2, // two subrects
                1, 1, 2, 2,
                5, 5, 1, 1,
            )
        RfbDecoders.decode(input, framebuffer, x = 0, y = 0, width = 16, height = 16, encoding = ENCODING_HEXTILE)
        assertEquals(0xFFD0E0F0, framebuffer.pixels[1 * 16 + 1])
        assertEquals(0xFFD0E0F0, framebuffer.pixels[2 * 16 + 2])
        assertEquals(0xFFD0E0F0, framebuffer.pixels[5 * 16 + 5])
        assertEquals(0xFF302010, framebuffer.pixels[15 * 16 + 15])
    }

    private fun streamOf(vararg bytes: Int): DataInputStream =
        DataInputStream(ByteArrayInputStream(bytes.map { it.toByte() }.toByteArray()))
}