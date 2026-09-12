package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

/** Connection details the built-in viewer needs to reach the guest desktop. */
data class DesktopSessionInfo(
    val port: Int,
    val password: String,
    val geometry: String = LocalRuntimeInstaller.DESKTOP_GEOMETRY,
)

/**
 * Lifecycle of the XFCE `Xvnc` server inside the shared Debian sandbox.
 *
 * The viewer turns this on when it opens: the server lives as a PRoot child of the app process, so
 * it goes away with it and never needs to be parked for later. It binds to loopback only
 * (`-localhost yes`) behind VNC authentication, and is stopped before any operation that replaces
 * the rootfs (update, reinstall, delete) so the old image can be moved aside cleanly.
 */
class DesktopSessionManager(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val portProbe: (Int) -> Boolean,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val processSignal: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    private val maxLogBytes: Long = 1_048_576L,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var startedAtMillis: Long? = null

    /**
     * Makes sure the viewer's VNC server is answering on the metadata port, starting it when it is
     * not. The probe keeps the start idempotent: a leftover server from a previous app run, or one
     * the watchdog already found healthy, is reused rather than stacked.
     */
    fun ensureRunning(): Result<DesktopSessionInfo> =
        accessCoordinator.read {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error("The Linux environment is not installed")
                val metadata = runtime.metadata
                require(metadata.desktopInstalled) {
                    "The desktop environment is not installed; install it from the runtime setup screen first"
                }
                require(metadata.desktopVncPort in 1..65535) { "Desktop VNC port is invalid" }
                val info =
                    DesktopSessionInfo(
                        port = metadata.desktopVncPort,
                        password = metadata.desktopVncPassword,
                    )
                if (portProbe(info.port)) return@runCatching info
                process?.let { current ->
                    if (current.isAlive) return@runCatching info
                    terminate(current)
                    process = null
                }
                start(runtime, info)
                info.also {
                    // Not reached when the server never comes up; the wait below throws first.
                }
            }
        }

    fun stop() {
        accessCoordinator.read {
            process?.let(::terminate)
            process = null
            startedAtMillis = null
        }
    }

    fun isRunning(): Boolean =
        process?.isAlive == true &&
            runCatching { portProbe(port()) }.getOrDefault(false)

    fun metrics(): DesktopSessionMetrics? {
        val current = process?.takeIf(Process::isAlive) ?: return null
        val pid = processId(current)
        return DesktopSessionMetrics(
            pid = pid,
            port = port(),
            uptimeMillis = (nowMillis() - (startedAtMillis ?: nowMillis())).coerceAtLeast(0L),
        )
    }

    private fun port(): Int =
        installedRuntimeProvider()?.metadata?.desktopVncPort ?: 0

    private fun start(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        info: DesktopSessionInfo,
    ) {
        val rootfs = runtime.rootfs
        val suite = runtime.commandSuite
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val logs = File(runtimeDirectory, "logs").apply { mkdirs() }
        val logFile = File(logs, "desktop-vnc.log")
        truncateLogFile(logFile, maxLogBytes)

        val displayNumber = info.port - 5900
        require(displayNumber in 0..99) { "Desktop VNC port is out of display range" }

        val command =
            buildList {
                add(suite.proot.absolutePath)
                add("--link2symlink")
                add("-r")
                add(rootfs.absolutePath)
                add("-b")
                add("/dev")
                add("-b")
                add("/proc")
                add("-b")
                add("/sys")
                add("-b")
                add("/system")
                add("-w")
                add("/root")
                add("/usr/bin/Xvnc")
                add(":$displayNumber")
                add("-localhost")
                add("yes")
                add("-SecurityTypes")
                add("VncAuth")
                add("-PasswordFile")
                add("/root/.vnc/passwd")
                add("-geometry")
                add(info.geometry)
                add("-depth")
                add("24")
                add("-rfbport")
                add(info.port.toString())
                add("-desktop")
                add("AndCode Debian")
                add("-noreset")
            }

        val builder =
            ProcessBuilder(command)
                .directory(runtimeDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        builder.environment().apply {
            clear()
            putAll(localRuntimeEnvironment(suite.environment(), prootTmp))
        }
        val started = builder.start()
        process = started
        startedAtMillis = nowMillis()
        waitUntilListening(started, info.port, logFile)
    }

    private fun waitUntilListening(
        process: Process,
        port: Int,
        logFile: File,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("The desktop VNC server exited during startup: ${tail(logFile)}")
            }
            if (portProbe(port)) return
            Thread.sleep(250)
        }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        error("The desktop VNC server did not start listening on port $port: ${tail(logFile)}")
    }

    private fun terminate(current: Process) {
        val roots =
            linkedSetOf<Long>().apply {
                processId(current)?.let(::add)
                addAll(findManagedRuntimeRootPids(runtimeDirectory))
            }
        val terminationOrder =
            roots
                .flatMap { rootPid ->
                    processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid) }
                }
                .distinct()

        if (current.isAlive) {
            current.destroy()
            current.waitFor(750, TimeUnit.MILLISECONDS)
        }
        terminationOrder.forEach { pid ->
            runCatching { processSignal(pid) }
        }
        if (current.isAlive && !current.waitFor(2, TimeUnit.SECONDS)) {
            current.destroyForcibly()
            current.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun tail(file: File): String =
        runCatching {
            file.readLines().takeLast(20).joinToString("\n")
        }.getOrDefault("No desktop VNC log was produced")
}

data class DesktopSessionMetrics(
    val pid: Long?,
    val port: Int,
    val uptimeMillis: Long,
)