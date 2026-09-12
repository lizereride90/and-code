package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VncPasswordTest {
    @Test
    fun `generate returns eight unambiguous characters`() {
        val first = VncPassword.generate()
        assertEquals(8, first.length)
        assertTrue(first.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789" })
        // Collision-free across a small run, so a fresh desktop is never locked behind a guess.
        assertNotEquals(first, VncPassword.generate())
    }

    @Test
    fun `obfuscation round-trips through the fixed TigerVNC key`() {
        val password = "hunter2!"
        val obfuscated = VncPassword.obfuscate(password)
        assertEquals(8, obfuscated.size)
        for ((index, byte) in obfuscated.withIndex()) {
            val recovered = (byte.toInt() xor VncPassword.OBFUSCATION_KEY[index].toInt()).toByte()
            val expected = password.getOrNull(index)?.code?.toByte() ?: 0x00
            assertEquals(expected, recovered)
        }
    }

    @Test
    fun `empty password obfuscates to exactly the raw key`() {
        assertArrayEquals(VncPassword.OBFUSCATION_KEY, VncPassword.obfuscate(""))
    }

    @Test
    fun `des key is eight bytes and bit inversion is self inverse`() {
        val key = VncPassword.desKey("r3v3rse")
        assertEquals(8, key.size)
        // reversing the bits of each reversed byte lands back on the original password bytes.
        for (index in key.indices) {
            val passwordByte = "r3v3rse".getOrNull(index)?.code?.toByte() ?: 0x00
            val doubleReversed =
                reverseBits(reverseBits(passwordByte.toInt())).toByte()
            assertEquals(passwordByte, doubleReversed)
        }
    }

    private fun reverseBits(byte: Int): Int {
        var value = byte and 0xFF
        var reversed = 0
        repeat(8) {
            reversed = (reversed shl 1) or (value and 1)
            value = value shr 1
        }
        return reversed
    }

    @Test
    fun `encryptChallenge produces a stable sixteen byte response`() {
        val challenge = ByteArray(16) { index -> index.toByte() }
        val first = VncPassword.encryptChallenge(challenge, "password")
        assertEquals(16, first.size)
        val second = VncPassword.encryptChallenge(challenge, "password")
        assertArrayEquals(first, second)
        val different = VncPassword.encryptChallenge(challenge, "passwore")
        assertNotEquals(String(first, Charsets.ISO_8859_1), String(different, Charsets.ISO_8859_1))
    }

    @Test
    fun `encryptChallenge rejects a malformed challenge`() {
        assertThrows(IllegalArgumentException::class.java) {
            VncPassword.encryptChallenge(ByteArray(8), "password")
        }
    }
}