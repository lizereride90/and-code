package com.yugahashimoto.andcode.runtime.local

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import java.security.SecureRandom

/**
 * VNC password helpers shared by the guest provisioning and the built-in RFB viewer.
 *
 * The guest stores its password in TigerVNC's obfuscated `passwd` file (every byte XORed with a
 * fixed key), while the viewer derives the same password through the VNC challenge/response over
 * DES. Splitting the pure helpers out keeps both sides unit-testable on the JVM.
 */
object VncPassword {
    private const val MAX_LENGTH = 8

    /**
     * The fixed XOR key TigerVNC/RealVNC obfuscate the password file with, in file byte order.
     */
    val OBFUSCATION_KEY: ByteArray = byteArrayOf(0xE4, 0x91, 0xDA, 0xF0, 0x9D, 0x23, 0xB8, 0xCC)

    private val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    /** A random 8-character password from an unambiguous alphabet. */
    fun generate(): String =
        buildString(capacity = MAX_LENGTH) {
            repeat(MAX_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /**
     * The obfuscated bytes TigerVNC expects in `/root/.vnc/passwd`.
     *
     * The password is padded to [MAX_LENGTH] with NUL bytes; a reader deobfuscates it back to the
     * trailing-padded form and only the first bytes feed the DES key, so zero padding is fine.
     */
    fun obfuscate(password: String): ByteArray =
        ByteArray(MAX_LENGTH) { index ->
            val plain = password.getOrNull(index)?.code?.takeIf { it in 0..0xFF }?.toByte() ?: 0x00
            plain.xor(OBFUSCATION_KEY[index])
        }

    /**
     * The DES key material for RFC 6143 VNC authentication.
     *
     * RFC 6143 requires the password's bytes, with each byte's bits reversed, padded to 8 bytes.
     */
    fun desKey(password: String): ByteArray =
        ByteArray(MAX_LENGTH) { index -> reverseBits(password.getOrNull(index)?.code?.toByte() ?: 0x00) }

    /**
     * Encrypts [challenge] (exactly 16 bytes) as VNC authentication demands: DES/ECB with the
     * bit-reversed, zero-padded password as the key, no padding.
     */
    fun encryptChallenge(
        challenge: ByteArray,
        password: String,
    ): ByteArray {
        require(challenge.size == 16) { "VNC challenge must be exactly 16 bytes" }
        val specification = DESKeySpec(desKey(password))
        val key = SecretKeyFactory.getInstance("DES").generateSecret(specification)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(byte: Byte): Byte {
        var value = byte.toInt() and 0xFF
        var reversed = 0
        repeat(8) {
            reversed = (reversed shl 1) or (value and 1)
            value = value shr 1
        }
        return reversed.toByte()
    }
}