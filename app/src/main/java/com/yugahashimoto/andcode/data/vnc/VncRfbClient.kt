package com.yugahashimoto.andcode.data.vnc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A minimal RFB 3.3/3.8 client (the protocol used by VNC). It handles the handshake (including
 * "VNC authentication"), requests the server's real pixel format as 32-bit true colour, requests
 * only Raw and CopyRect encodings (both universally supported and cheap on localhost), and renders
 * framebuffer updates into a [Framebuffer].
 *
 * The connection runs on [scope]; input (mouse/keyboard) calls are thread-safe and are dispatched
 * onto the IO dispatcher so they never block the UI thread.
 */
class VncRfbClient(
    private val device: VncDevice,
    private val framebuffer: Framebuffer,
    private val scope: CoroutineScope,
    private val onState: (VncConnectionState, String?) -> Unit,
    private val onServerName: (String) -> Unit,
) {
    @Volatile
    var connected: Boolean = false
        private set

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var connectionJob: Job? = null
    private val outputLock = Any()
    private var serverName: String = ""

    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob =
            scope.launch(Dispatchers.IO) {
                runConnection()
            }
    }

    fun disconnect() {
        connected = false
        runCatching { socket?.close() }
        connectionJob?.cancel()
    }

    fun sendPointerEvent(
        buttonMask: Int,
        x: Int,
        y: Int,
    ) {
        scope.launch(Dispatchers.IO) {
            safeWrite { out ->
                out.writeByte(5)
                out.writeByte(buttonMask and 0xFF)
                out.writeShort(x.coerceIn(0, 0xFFFF))
                out.writeShort(y.coerceIn(0, 0xFFFF))
            }
        }
    }

    fun sendKey(
        down: Boolean,
        keysym: Int,
    ) {
        scope.launch(Dispatchers.IO) {
            safeWrite { out ->
                writeKeyEvent(out, down, keysym)
            }
        }
    }

    /**
     * Sends a sequence of key events in one ordered write so that press + release pairs can never
     * race and arrive out of order (they run on the same IO coroutine).
     */
    fun sendKeySequence(keys: List<Pair<Boolean, Int>>) {
        if (keys.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            safeWrite { out ->
                keys.forEach { (down, keysym) -> writeKeyEvent(out, down, keysym) }
            }
        }
    }

    fun sendKeyChar(character: Char) {
        val keysym = keysymFor(character) ?: return
        sendKeySequence(listOf(true to keysym, false to keysym))
    }

    /** Types [character], optionally wrapped in Ctrl/Alt. The whole sequence is sent in one write. */
    fun sendText(
        character: Char,
        ctrl: Boolean = false,
        alt: Boolean = false,
    ) {
        val keysym = keysymFor(character) ?: return
        val code = character.code
        val ctrlLetter = ctrl && code in 'a'.code..'z'.code
        val altLetter = alt && code in 'a'.code..'z'.code
        if (!ctrlLetter && !altLetter) {
            sendKeySequence(listOf(true to keysym, false to keysym))
            return
        }
        val events =
            buildList {
                if (ctrlLetter) add(true to KEYSYM_CTRL)
                if (altLetter) add(true to KEYSYM_ALT)
                add(true to keysym)
                add(false to keysym)
                if (altLetter) add(false to KEYSYM_ALT)
                if (ctrlLetter) add(false to KEYSYM_CTRL)
            }
        sendKeySequence(events)
    }

    private fun writeKeyEvent(
        out: DataOutputStream,
        down: Boolean,
        keysym: Int,
    ) {
        out.writeByte(4)
        out.writeByte(if (down) 1 else 0)
        out.writeShort(0)
        out.writeInt(keysym)
    }

    private fun keysymFor(character: Char): Int? {
        val code = character.code
        return when (code) {
            '\n'.code, '\r'.code -> 0xFF0D
            '\t'.code -> 0xFF09
            '\b'.code -> 0xFF08
            ' '.code -> 0x20
            in 0x21..0xFF -> code
            in 0x100..0x10FFFF -> 0x01000000 or code
            else -> null
        }
    }

    // ------------------------------------------------------------------ connection

    private fun runConnection() {
        var sock: Socket? = null
        try {
            connected = true
            sock = Socket()
            sock.tcpNoDelay = true
            sock.soTimeout = 30_000
            sock.connect(InetSocketAddress(device.host, device.port), 15_000)
            socket = sock
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            inputStream = input
            outputStream = output

            handshake(input, output)
            onState(VncConnectionState.CONNECTED, null)

            sendPixelFormat(output)
            sendEncodings(output)
            sendFramebufferUpdateRequest(output, incremental = false)
            messageLoop(input, output)
        } catch (_: SocketTimeoutException) {
            onState(VncConnectionState.FAILED, "Connection to ${device.hostPort} timed out")
        } catch (_: EOFException) {
            if (connected) onState(VncConnectionState.FAILED, "Connection to ${device.hostPort} was closed")
        } catch (error: IOException) {
            if (connected) onState(VncConnectionState.FAILED, "Network error: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            if (connected) onState(VncConnectionState.FAILED, error.message ?: "Connection failed")
        } finally {
            connected = false
            runCatching { sock?.close() }
        }
    }

    private fun handshake(
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        val versionLine = ByteArray(12)
        input.readFully(versionLine)
        val version = String(versionLine, Charsets.US_ASCII).trim()
        val protocol3 = version.startsWith("RFB 003.")
        require(protocol3) { "Unsupported VNC protocol version: $version" }
        val minor = version.substringAfter("RFB 003.").substringBefore('\u0000').let { it.toIntOrNull() ?: 3 }
        output.writeBytes("RFB 003.008\n")
        output.flush()

        if (minor >= 8) {
            val securityTypesCount = input.readUnsignedByte()
            val securityTypes =
                if (securityTypesCount > 0) {
                    ByteArray(securityTypesCount).also { input.readFully(it) }.map { it.toInt() and 0xFF }
                } else {
                    readReason(input)
                    error("VNC server does not offer any security types")
                }
            val chosen = chooseSecurityType(securityTypes)

            when (chosen) {
                SECURITY_NONE -> {
                    write { out -> out.writeInt(SECURITY_NONE) }
                    checkSecurityResult(input)
                }
                SECURITY_VNC_AUTH -> {
                    write { out -> out.writeInt(SECURITY_VNC_AUTH) }
                    performVncAuth(input)
                    checkSecurityResult(input)
                }
                else -> error("No supported security type offered by server")
            }
        } else {
            // RFB 3.3/3.7: a single security type, or 0 meaning failure (followed by a reason).
            val securityType = input.readInt()
            if (securityType == 0) readReason(input)
            when (securityType) {
                SECURITY_NONE -> Unit
                SECURITY_VNC_AUTH -> performVncAuth(input)
                else -> error("Unsupported security type $securityType")
            }
        }

        // ClientInit: shared flag.
        write { out -> out.writeByte(1) }

        // ServerInit.
        val serverWidth = input.readUnsignedShort()
        val serverHeight = input.readUnsignedShort()
        framebuffer.resize(serverWidth, serverHeight)
        val pixelFormat = ByteArray(16)
        input.readFully(pixelFormat)
        val nameLength = input.readInt()
        val nameBytes = ByteArray(nameLength.coerceAtLeast(0))
        input.readFully(nameBytes)
        serverName = String(nameBytes, Charsets.UTF_8)
        onServerName(serverName)
    }

    private fun chooseSecurityType(offered: List<Int>): Int {
        val preferred = if (device.isPasswordSet) SECURITY_VNC_AUTH else SECURITY_NONE
        if (preferred in offered) return preferred
        return offered.firstOrNull { it == SECURITY_NONE || it == SECURITY_VNC_AUTH } ?: -1
    }

    private fun performVncAuth(input: DataInputStream) {
        val challenge = ByteArray(16)
        input.readFully(challenge)
        val passwordBytes = device.password.toByteArray(Charsets.UTF_8)
        val response = DesCipher.vncAuthResponse(challenge, passwordBytes)
        write { out -> out.write(response) }
    }

    private fun checkSecurityResult(input: DataInputStream) {
        val result = input.readInt()
        if (result != 0) {
            val reason = readReason(input)
            error(reason.ifBlank { "VNC server rejected the connection (status $result)" })
        }
    }

    private fun readReason(input: DataInputStream): String {
        val reasonLength = input.readInt()
        if (reasonLength <= 0 || reasonLength > 1_048_576) return ""
        val bytes = ByteArray(reasonLength)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun sendPixelFormat(output: DataOutputStream) {
        // 32-bit true colour, little-endian byte order, depth 24.
        val bpp = 32
        val buffer = java.nio.ByteBuffer.allocate(20)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(bpp.toByte())
        buffer.put(24) // depth
        buffer.put(0) // big-endian flag
        buffer.put(1) // true-colour flag
        buffer.putShort(255) // red max
        buffer.putShort(255) // green max
        buffer.putShort(255) // blue max
        buffer.put(16) // red shift
        buffer.put(8) // green shift
        buffer.put(0) // blue shift
        buffer.put(0) // padding
        buffer.put(0)
        buffer.put(0)
        write { out -> out.write(buffer.array()) }
    }

    private fun sendEncodings(output: DataOutputStream) {
        // Raw + CopyRect cover every server; the pseudo-encodings let us learn resizes and names.
        val encodings =
            intArrayOf(
                0, // Raw
                1, // CopyRect
                PSEUDO_DESKTOP_SIZE,
                PSEUDO_EXT_DESKTOP_SIZE,
                PSEUDO_DESKTOP_NAME,
            )
        synchronized(outputLock) {
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(encodings.size)
            encodings.forEach { output.writeInt(it) }
            output.flush()
        }
    }

    private fun sendFramebufferUpdateRequest(
        output: DataOutputStream,
        incremental: Boolean,
    ) {
        synchronized(outputLock) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(framebuffer.width)
            output.writeShort(framebuffer.height)
            output.flush()
        }
    }

    // ------------------------------------------------------------------ message loop

    private fun messageLoop(
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        // A watchful fallback: if a request goes unanswered (e.g. the server missed one), re-asking
        // for an incremental update every few seconds keeps the session alive.
        val watchdog =
            scope.launch(Dispatchers.IO) {
                while (connected) {
                    delay(4_000)
                    if (connected) runCatching { sendFramebufferUpdateRequest(output, incremental = true) }
                }
            }
        try {
            while (connected) {
                when (val messageType = input.readUnsignedByte()) {
                    0 -> handleFramebufferUpdate(input, output)
                    2 -> Unit // Bell
                    3 -> {
                        val length = input.readInt()
                        if (length > 0 && length <= 1_048_576) input.skipBytes(length)
                    }
                    else -> throw IOException("Unknown RFB server message type $messageType")
                }
            }
        } finally {
            watchdog.cancel()
        }
    }

    private fun handleFramebufferUpdate(
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        input.skipBytes(1) // padding
        val rectCount = input.readUnsignedShort()
        repeat(rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            when (encoding) {
                0 -> readRawRect(input, x, y, w, h)
                1 -> readCopyRect(input, x, y, w, h)
                PSEUDO_DESKTOP_SIZE -> framebuffer.resize(w, h) // DesktopSize
                PSEUDO_EXT_DESKTOP_SIZE -> readExtendedDesktopSize(input, w, h) // ExtendedDesktopSize
                PSEUDO_DESKTOP_NAME -> readDesktopName(input) // DesktopName
                else -> throw IOException("Unsupported RFB encoding $encoding")
            }
        }
        // Ask for the next change now that this frame is applied.
        sendFramebufferUpdateRequest(output, incremental = true)
    }

    private fun readRawRect(
        input: DataInputStream,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        if (w <= 0 || h <= 0) return
        if (x + w > framebuffer.width || y + h > framebuffer.height) {
            framebuffer.resize(maxOf(framebuffer.width, x + w), maxOf(framebuffer.height, y + h))
        }
        val pixelCount = w * h
        val raw = ByteArray(pixelCount * 4)
        input.readFully(raw)
        val argb = IntArray(pixelCount)
        var pixelIndex = 0
        var byteIndex = 0
        repeat(pixelCount) {
            val blue = raw[byteIndex].toInt() and 0xFF
            val green = raw[byteIndex + 1].toInt() and 0xFF
            val red = raw[byteIndex + 2].toInt() and 0xFF
            argb[pixelIndex++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            byteIndex += 4
        }
        framebuffer.putRowPixels(x, y, w, h, argb)
    }

    private fun readCopyRect(
        input: DataInputStream,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        framebuffer.copyRect(x, y, w, h, srcX, srcY)
    }

    private fun readExtendedDesktopSize(
        input: DataInputStream,
        w: Int,
        h: Int,
    ) {
        val screenCount = input.readUnsignedByte()
        input.skipBytes(1)
        repeat(screenCount.coerceAtLeast(0)) {
            input.skipBytes(4) // screen id
            input.skipBytes(4) // x
            input.skipBytes(4) // y
            input.skipBytes(2) // width
            input.skipBytes(2) // height
        }
        if (w > 0 && h > 0) framebuffer.resize(w, h)
    }

    private fun readDesktopName(input: DataInputStream) {
        val length = input.readInt()
        if (length > 0 && length <= 65_535) {
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val name = String(bytes, Charsets.UTF_8)
            if (name.isNotBlank()) onServerName(name)
        }
    }

    private fun write(block: (DataOutputStream) -> Unit) {
        synchronized(outputLock) {
            val out = outputStream ?: return
            if (!connected) return
            block(out)
            out.flush()
        }
    }

    private fun safeWrite(block: (DataOutputStream) -> Unit) {
        synchronized(outputLock) {
            val out = outputStream ?: return
            if (!connected) return
            try {
                block(out)
                out.flush()
            } catch (_: IOException) {
                // Socket raced to a close; runConnection surfaces the failure.
            }
        }
    }

    private companion object {
        const val SECURITY_NONE = 1
        const val SECURITY_VNC_AUTH = 2

        const val KEYSYM_CTRL = 0xFFE3
        const val KEYSYM_ALT = 0xFFE9

        // RFB pseudo-encoding numbers (as 32-bit values written to the wire; the high byte is 0xFF).
        private val PSEUDO_DESKTOP_SIZE: Int = 0xFFFFFF21
        private val PSEUDO_EXT_DESKTOP_SIZE: Int = 0xFFFF0021
        private val PSEUDO_DESKTOP_NAME: Int = 0xFFFFFF20
    }
}

enum class VncConnectionState {
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED,
}
