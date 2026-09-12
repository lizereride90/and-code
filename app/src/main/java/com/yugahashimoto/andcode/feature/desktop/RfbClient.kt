package com.yugahashimoto.andcode.feature.desktop

import com.yugahashimoto.andcode.runtime.local.VncPassword
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val PROTOCOL_VERSION = "RFB 003.008"
private const val SECURITY_TYPE_NONE = 1
private const val SECURITY_TYPE_VNC_AUTH = 2
private const val MESSAGE_FRAMEBUFFER_UPDATE = 0
private const val MESSAGE_SET_ENCODINGS = 2
private const val MESSAGE_BELL = 2
private const val MESSAGE_SERVER_CUT_TEXT = 3
private const val MESSAGE_FRAMEBUFFER_UPDATE_REQUEST = 3
private const val MESSAGE_KEY_EVENT = 4
private const val MESSAGE_POINTER_EVENT = 5
private const val MESSAGE_SET_PIXEL_FORMAT = 6

/** Bit masks for the RFB pointer-event button mask. */
object RfbButtons {
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val SCROLL_UP = 8
    const val SCROLL_DOWN = 16
}

enum class RfbConnectionState {
    DISCONNECTED,
    CONNECTED,
    CLOSED,
}

/**
 * Minimal RFC 6143 VNC client: handshake with VNC authentication, then Raw/CopyRect/RRE/Hextile
 * framebuffer updates and outgoing pointer/keyboard events.
 *
 * A dedicated daemon thread owns the receive loop; pointer and key events may be sent from any
 * thread and are serialised on the socket output. The guest desktop binds loopback in this app's
 * own process, which is why no TLS or exotic security types are needed.
 */
class RfbClient(
    host: String,
    port: Int,
    private val password: String,
    private val connectTimeoutMillis: Int = 8_000,
    private val socketFactory: (String, Int, Int) -> Socket = { h, p, timeout ->
        Socket().apply { connect(InetSocketAddress(h, p), timeout) }
    },
    /** Called from the worker thread once ServerInit has announced the framebuffer dimensions. */
    private val onConnected: (Framebuffer) -> Unit,
    /** Called after every decoded FramebufferUpdate containing at least one drawn rect. */
    private val onFrame: (Framebuffer) -> Unit,
    private val onDisconnected: (Throwable?) -> Unit,
) {
    @Volatile
    private var state = RfbConnectionState.DISCONNECTED

    @Volatile
    private var socket: Socket? = null

    private lateinit var output: DataOutputStream

    private var framebuffer: Framebuffer? = null

    fun isConnected(): Boolean = state == RfbConnectionState.CONNECTED

    fun framebuffer(): Framebuffer? = framebuffer

    /** Runs the handshake and receive loop in a daemon thread until it is closed or fails. */
    fun start() {
        if (state != RfbConnectionState.DISCONNECTED) return
        state = RfbConnectionState.CONNECTED
        Thread({
            try {
                runConnection(host, port)
            } catch (error: Throwable) {
                if (error is InterruptedException) return@Thread
                state = RfbConnectionState.CLOSED
                onDisconnected(error)
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }, "android-vnc-receiver").apply { isDaemon = true }.start()
    }

    fun close() {
        state = RfbConnectionState.CLOSED
        runCatching { socket?.close() }
    }

    /**
     * Reports a pointer event. [buttons] is the bitmask of the buttons currently held - release is
     * just the same call with an empty mask. Coordinates are in framebuffer (desktop) pixels.
     */
    fun sendPointerEvent(
        buttons: Int,
        x: Int,
        y: Int,
    ) {
        synchronizedOutput {
            writeByte(MESSAGE_POINTER_EVENT)
            writeByte(0)
            writeShort(buttons)
            writeShort(x.coerceIn(0, 0xFFFF))
            writeShort(y.coerceIn(0, 0xFFFF))
            flush()
        }
    }

    fun sendKeyEvent(
        keysym: Int,
        isDown: Boolean,
    ) {
        synchronizedOutput {
            writeByte(MESSAGE_KEY_EVENT)
            writeByte(if (isDown) 1 else 0)
            writeShort(0)
            writeInt(keysym)
            flush()
        }
    }

    /** Types [text] by sending one key-down/key-up pair per character as Unicode keysyms. */
    fun sendText(text: String) {
        text.forEach { character ->
            sendKeyEvent(keysymForCharacter(character), isDown = true)
            sendKeyEvent(keysymForCharacter(character), isDown = false)
        }
    }

    private fun synchronizedOutput(block: DataOutputStream.() -> Unit) {
        if (state != RfbConnectionState.CONNECTED) return
        synchronized(this) {
            runCatching { output.block() }
        }
    }

    private fun runConnection(
        host: String,
        port: Int,
    ) {
        val connectedSocket = socketFactory(host, port, connectTimeoutMillis)
        socket = connectedSocket
        val input = DataInputStream(BufferedInputStream(connectedSocket.getInputStream(), 64 * 1024))
        output = DataOutputStream(BufferedOutputStream(connectedSocket.getOutputStream(), 16 * 1024))

        readServerVersion(input)
        negotiateSecurity(input)
        output.writeByte(1) // shared = true
        val (width, height) = readServerInit(input)
        val desktop = Framebuffer(width, height)
        framebuffer = desktop

        setPixelFormat()
        setEncodings()
        requestUpdate(incremental = false, 0, 0, width, height)

        var changed = false
        while (state == RfbConnectionState.CONNECTED) {
            val messageType = input.readUnsignedByte()
            when (messageType) {
                MESSAGE_FRAMEBUFFER_UPDATE -> {
                    input.readUnsignedByte() // padding
                    val rectangleCount = input.readUnsignedShort()
                    var rectCount = 0
                    while (rectCount < rectangleCount) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        val encoding = input.readInt()
                        if (w == 0 || h == 0 || encoding < 0) {
                            rectCount++
                            continue
                        }
                        RfbDecoders.decode(input, desktop, x, y, w, h, encoding)
                        changed = true
                        rectCount++
                    }
                    if (changed) {
                        changed = false
                        onFrame(desktop)
                    }
                    requestUpdate(incremental = true, 0, 0, width, height)
                }
                MESSAGE_BELL -> Unit
                MESSAGE_SERVER_CUT_TEXT -> {
                    input.readFully(ByteArray(3))
                    val length = input.readInt()
                    if (length > 0 && length <= (256 * 1024)) {
                        input.readFully(ByteArray(length))
                    }
                }
                else -> {
                    // Unknown server-to-client message: a well-written client should ignore it.
                }
            }
        }
    }

    private fun readServerVersion(input: DataInputStream) {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val version = String(versionBytes, Charsets.ASCII)
        if (!version.startsWith("RFB ")) {
            error("The desktop did not speak VNC: $version")
        }
        output.writeBytes(PROTOCOL_VERSION)
        output.flush()
    }

    private fun negotiateSecurity(input: DataInputStream) {
        val securityTypeCount = input.readUnsignedByte()
        if (securityTypeCount == 0) {
            val reason = readFailureReason(input)
            error("The desktop refused the connection: $reason")
        }
        val availableTypes = ArrayList<Int>(securityTypeCount)
        var index = 0
        while (index < securityTypeCount) {
            availableTypes += input.readUnsignedByte()
            index++
        }
        val chosen =
            SECURITY_TYPE_VNC_AUTH.takeIf { it in availableTypes }
                ?: SECURITY_TYPE_NONE.takeIf { it in availableTypes }
                ?: error("The desktop offers no supported security type: $availableTypes")
        output.writeByte(chosen)
        output.flush()

        if (chosen == SECURITY_TYPE_VNC_AUTH) {
            val challenge = ByteArray(16)
            input.readFully(challenge)
            val response = VncPassword.encryptChallenge(challenge, password)
            output.write(response)
            output.flush()
        }
        val result = input.readInt()
        if (result != 0) {
            val reason = readFailureReason(input)
            error("VNC authentication failed: $reason")
        }
    }

    private fun readFailureReason(input: DataInputStream): String {
        if (input.available() >= 4) {
            val length = input.readInt()
            if (length in 1..(64 * 1024)) {
                val bytes = ByteArray(length)
                input.readFully(bytes)
                return String(bytes, Charsets.UTF_8)
            }
        }
        return "unknown"
    }

    private fun readServerInit(input: DataInputStream): Pair<Int, Int> {
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.readFully(ByteArray(16)) // server pixel format - the viewer sets its own below
        val nameLength = input.readInt()
        if (nameLength > 0 && nameLength <= 1024) {
            input.readFully(ByteArray(nameLength))
        }
        return width to height
    }

    private fun setPixelFormat() {
        synchronizedOutput {
            writeByte(MESSAGE_SET_PIXEL_FORMAT)
            // 3 bytes padding
            writeByte(0)
            writeByte(0)
            writeByte(0)
            writeByte(32) // bits per pixel
            writeByte(24) // depth
            writeByte(0) // big endian false
            writeByte(1) // true colour
            writeShort(255) // red max
            writeShort(255) // green max
            writeShort(255) // blue max
            writeByte(16) // red shift
            writeByte(8) // green shift
            writeByte(0) // blue shift
            writeByte(0) // padding 1
            writeByte(0) // padding 2
            writeByte(0) // padding 3
            flush()
        }
    }

    private fun setEncodings() {
        synchronizedOutput {
            writeByte(MESSAGE_SET_ENCODINGS)
            writeByte(0)
            writeByte(0)
            writeShort(4)
            writeInt(ENCODING_RAW)
            writeInt(ENCODING_COPY_RECT)
            writeInt(ENCODING_RRE)
            writeInt(ENCODING_HEXTILE)
            flush()
        }
    }

    private fun requestUpdate(
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        synchronizedOutput {
            writeByte(MESSAGE_FRAMEBUFFER_UPDATE_REQUEST)
            writeByte(if (incremental) 1 else 0)
            writeShort(x)
            writeShort(y)
            writeShort(width)
            writeShort(height)
            flush()
        }
    }

    companion object {
        /** Maps a typed character to an X11 keysym, including the Unicode range for non-Latin-1. */
        fun keysymForCharacter(character: Char): Int {
            val code = character.code
            return when (code) {
                0x08 -> 0xFF08
                0x09 -> 0xFF09
                0x0D -> 0xFF0D
                in 0x20 until 0x100 -> code
                else -> 0x01000000 + code
            }
        }
    }
}