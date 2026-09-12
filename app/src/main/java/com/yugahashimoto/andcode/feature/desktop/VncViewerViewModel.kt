package com.yugahashimoto.andcode.feature.desktop

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.runtime.local.DesktopSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VncPointerMode { LEFT, MIDDLE, RIGHT }

sealed interface VncPhase {
    data object NotInstalled : VncPhase
    data object Installing : VncPhase
    data object Starting : VncPhase
    data object Connecting : VncPhase
    data class Connected(
        val frameVersion: Long,
        val image: androidx.compose.ui.graphics.ImageBitmap?,
    ) : VncPhase
    data class Failed(val message: String) : VncPhase
    data object Closed : VncPhase
}

data class VncViewerUiState(
    val phase: VncPhase = VncPhase.NotInstalled,
    val desktopWidth: Int = 0,
    val desktopHeight: Int = 0,
    val keyboardVisible: Boolean = false,
    val pointerMode: VncPointerMode = VncPointerMode.LEFT,
    val typingBuffer: String = "",
)

class VncViewerViewModel(
    private val sessionManager: DesktopSessionManager,
    private val desktopInstalled: () -> Boolean,
    private val installDesktop: () -> Unit,
    private val connectTimeoutMillis: Int = 8_000,
    private val installPollIntervalMillis: Long = 2_000L,
    private val installPollTimeoutMillis: Long = 15 * 60_000L,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VncViewerUiState())
    val state: StateFlow<VncViewerUiState> = mutableState.asStateFlow()

    @Volatile
    private var client: RfbClient? = null

    @Volatile
    private var bitmap: Bitmap? = null

    private var installPollJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { connect() }
    }

    fun retry() {
        closeClient()
        viewModelScope.launch(Dispatchers.IO) { connect() }
    }

    fun installAndConnect() {
        mutableState.update { it.copy(phase = VncPhase.Installing) }
        installDesktop()
        pollForInstalledDesktop()
    }

    fun close() {
        installPollJob?.cancel()
        installPollJob = null
        closeClient()
        mutableState.update { it.copy(phase = VncPhase.Closed) }
    }

    fun toggleKeyboard() {
        mutableState.update { it.copy(keyboardVisible = !it.keyboardVisible) }
    }

    fun setPointerMode(mode: VncPointerMode) {
        mutableState.update { it.copy(pointerMode = mode) }
    }

    fun sendPointerEvent(
        buttons: Int,
        x: Int,
        y: Int,
    ) {
        client?.sendPointerEvent(buttons, x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    fun sendKeysym(
        keysym: Int,
        isDown: Boolean,
    ) {
        client?.sendKeyEvent(keysym, isDown)
    }

    fun sendCharacter(character: Char) {
        client?.sendKeyEvent(RfbClient.keysymForCharacter(character), isDown = true)
        client?.sendKeyEvent(RfbClient.keysymForCharacter(character), isDown = false)
    }

    fun sendLine() {
        sendCharacter('\r')
        mutableState.update { it.copy(typingBuffer = "") }
    }

    fun updateTypingBuffer(newValue: String) {
        val previous = mutableState.value.typingBuffer
        mutableState.update { it.copy(typingBuffer = newValue) }
        when {
            newValue.startsWith(previous) -> newValue.drop(previous.length).forEach(::sendCharacter)
            previous.startsWith(newValue) -> {
                repeat(previous.length - newValue.length) { backspaceOnlyRemote() }
            }
            else -> {
                repeat(previous.length) { backspaceOnlyRemote() }
                newValue.forEach(::sendCharacter)
            }
        }
    }

    fun backspace() {
        backspaceOnlyRemote()
        mutableState.update { state -> state.copy(typingBuffer = state.typingBuffer.dropLast(1)) }
    }

    private fun backspaceOnlyRemote() {
        sendKeysym(0xFF08, isDown = true)
        sendKeysym(0xFF08, isDown = false)
    }

    private suspend fun connect() {
        if (!desktopInstalled()) {
            mutableState.update { it.copy(phase = VncPhase.NotInstalled) }
            return
        }
        mutableState.update { it.copy(phase = VncPhase.Starting) }
        val session =
            runCatching { sessionManager.ensureRunning().getOrThrow() }
                .getOrElse { error ->
                    mutableState.update { it.copy(phase = VncPhase.Failed(error.message ?: "Could not start the desktop")) }
                    return
                }
        mutableState.update { it.copy(phase = VncPhase.Connecting) }
        withContext(Dispatchers.IO) {
            connectClient(session.port, session.password)
        }
    }

    private suspend fun connectClient(
        port: Int,
        password: String,
    ) {
        closeClient()
        val connection =
            RfbClient(
                host = "127.0.0.1",
                port = port,
                password = password,
                connectTimeoutMillis = connectTimeoutMillis,
                onConnected = { framebuffer ->
                    createBitmap(framebuffer)
                },
                onFrame = { framebuffer ->
                    bitmap?.setPixels(
                        framebuffer.pixels,
                        0,
                        framebuffer.width,
                        0,
                        0,
                        framebuffer.width,
                        framebuffer.height,
                    )
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        mutableState.update { state ->
                            when (val phase = state.phase) {
                                is VncPhase.Connected ->
                                    state.copy(phase = phase.copy(frameVersion = phase.frameVersion + 1))
                                else -> state
                            }
                        }
                    }
                },
                onDisconnected = { error ->
                    val message = error?.message ?: "The desktop closed the connection"
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        mutableState.update { state -> state.copy(phase = VncPhase.Failed(message)) }
                    }
                    bitmap = null
                },
            )
        client = connection
        connection.start()
    }

    private fun createBitmap(framebuffer: Framebuffer) {
        val created =
            Bitmap.createBitmap(framebuffer.width, framebuffer.height, Bitmap.Config.ARGB_8888)
        bitmap = created
        viewModelScope.launch(Dispatchers.Main.immediate) {
            mutableState.update {
                it.copy(
                    desktopWidth = framebuffer.width,
                    desktopHeight = framebuffer.height,
                    phase = VncPhase.Connected(frameVersion = 0L, image = created.asImageBitmap()),
                )
            }
        }
    }

    private fun pollForInstalledDesktop() {
        installPollJob?.cancel()
        val deadline = System.currentTimeMillis() + installPollTimeoutMillis
        installPollJob =
            viewModelScope.launch(Dispatchers.IO) {
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (desktopInstalled()) {
                        connect()
                        return@launch
                    }
                    delay(installPollIntervalMillis)
                }
                if (isActive && !desktopInstalled()) {
                    mutableState.update {
                        it.copy(
                            phase =
                                VncPhase.Failed(
                                    "The desktop installation did not finish. Check the runtime setup screen for details.",
                                ),
                        )
                    }
                }
            }
    }

    private fun closeClient() {
        client?.close()
        client = null
    }
}