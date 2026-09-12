package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Provisions the official Claude Code package into the shared Debian sandbox.
 *
 * Anthropic publishes Claude Code on npm, so this is a global that install which npm's
 * `--prefix /usr/local` points at `/usr/local/bin/claude`, where [CLAUDE_BINARY] names it. The
 * sandbox carries Node.js for exactly this reason: the shared Debian install adds `nodejs` and
 * `npm` as soon as Claude Code is among the requested agents, and [installInto] falls back to
 * installing them with apt when it is added to an older, minimal runtime instead.
 *
 * The instructions here deliberately run Node's `npm` rather than any bundled binary: the official
 * package ships a platform-native launcher, but the npm install hook is the same everywhere and
 * the update flow can rely on `@latest` resolving instead of a package channel moving under it.
 */
object ClaudeCodeInstaller {
    const val CLAUDE_BINARY = "/usr/local/bin/claude"

    /**
     * Preloaded into Claude Code so its DNS resolver has usable servers.
     *
     * The CLI's resolver intermittently times out against api.anthropic.com on Android even when
     * the system resolver is fine. Pointing it at explicit servers avoids a hang that otherwise
     * looks like the agent simply never answering.
     */
    const val DNS_PRELOAD = "/usr/local/share/claude-setdns.js"

    private const val NPM_PACKAGE = "@anthropic-ai/claude-code"
    const val NPM = "/usr/bin/npm"

    /** `$` in a shell snippet, spelled out because these scripts live in Kotlin raw strings. */
    private const val S = "$"

    /**
     * Package-manager half of the install, split out so tests can drive it with a stub `npm`.
     *
     * The npm install exits non-zero whenever the registry or a dependency fails; unlike a repo
     * package there is no broken-package database to survive, so a failure here is final and
     * reported as-is.
     */
    internal fun installPackageCommands(
        npm: String = NPM,
        claude: String = CLAUDE_BINARY,
    ) = """
        if ! $npm install -g --prefix /usr/local --no-fund --no-audit $NPM_PACKAGE@latest; then
          echo 'and-code: npm failed to install $NPM_PACKAGE' >&2
          exit 1
        fi
        $claude --version
        """.trimIndent()

    /**
     * Package-manager half of the update.
     *
     * Same global install pinned to `@latest`; npm is idempotent, so re-running it on an already
     * current install is a cheap no-op that still verifies the binary runs.
     */
    internal fun updatePackageCommands(
        npm: String = NPM,
        claude: String = CLAUDE_BINARY,
    ) = """
        if ! $npm install -g --prefix /usr/local --no-fund --no-audit "$S{NPM_PACKAGE}@latest"; then
          echo 'and-code: npm failed to upgrade $NPM_PACKAGE' >&2
          exit 1
        fi
        $claude --version
        """.trimIndent()

    /**
     * Apt step that guarantees Node.js and npm in the sandbox, run only against existing runtimes
     * that predate the nodejs/npm requirement.
     *
     * Fresh installs always carry these when Claude Code is requested, so the host-side existence
     * check keeps this a no-op on the normal path.
     */
    internal fun ensureNodeRuntimeCommands(node: String) =
        """
        set -e
        $node -e '' 2>/dev/null && exit 0
        apt-get update -qq && \
          DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends nodejs npm && \
          rm -rf /var/lib/apt/lists/*
        """.trimIndent()

    internal val INSTALL_SCRIPT =
        script(
            """
            set -e
            """,
            installPackageCommands(),
        )

    internal val UPDATE_SCRIPT =
        script(
            """
            set -e
            """,
            updatePackageCommands(),
        )

    /** Joins script sections, trimming each so they can be indented to match their surroundings. */
    private fun script(vararg sections: String) = sections.joinToString("\n") { it.trimIndent().trim() }

    /**
     * Diagnostics appended to the log when the operation failed, so the tail of a failed run names
     * the state the sandbox is in rather than just the npm error.
     */
    private fun diagnosticsScript() =
        """
        echo '--- and-code npm diagnostics ---'
        echo '-- df -h / --'
        df -h / 2>&1 || true
        echo '-- claude binary ---'
        ls -l $CLAUDE_BINARY 2>&1 || true
        echo '-- npm global list --'
        $NPM list -g --depth=0 2>&1 || true
        """.trimIndent()

    /** Steps reported while [installInto] runs, so the UI can show more than a spinner. */
    enum class Step {
        ADDING_REPOSITORY,
        DOWNLOADING_PACKAGE,
        VERIFYING,
    }

    /**
     * Installs Claude Code into [rootfs] using [suite]'s PRoot.
     *
     * Used both during a fresh sandbox install (where [rootfs] is still the staging directory) and
     * when adding Claude Code to a sandbox that is already active.
     */
    fun installInto(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        onStep: (Step) -> Unit = {},
        timeoutMinutes: Long = 15,
    ) {
        onStep(Step.ADDING_REPOSITORY)
        ensureNodeIn(rootfs, suite, runtimeDirectory, timeoutMinutes)
        val log =
            File(runtimeDirectory, "logs/claude-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        onStep(Step.DOWNLOADING_PACKAGE)
        val exitCode = runInRootfs(INSTALL_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        onStep(Step.VERIFYING)
        if (exitCode != 0) {
            collectDiagnostics(rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        }
        check(exitCode == 0) { failureMessage("installation", exitCode, log) }
        check(File(rootfs, CLAUDE_BINARY.removePrefix("/")).isFile) {
            "Claude Code reported success but $CLAUDE_BINARY is missing"
        }
        ensureDnsPreload(rootfs)
    }

    /**
     * Installs Node.js and npm into an existing sandbox that lacks them, so npm-based Claude
     * installs never fail on a minimal runtime that predates the requirement.
     */
    private fun ensureNodeIn(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        timeoutMinutes: Long,
    ) {
        val nodeBinary =
            listOf("usr/bin/node", "usr/local/bin/node")
                .map { File(rootfs, it) }
                .firstOrNull { it.isFile }
        if (nodeBinary != null) return
        val log =
            File(runtimeDirectory, "logs/claude-node-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val exitCode = runInRootfs(ensureNodeRuntimeCommands("/usr/bin/node"), rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        check(exitCode == 0) {
            "Claude Code needs Node.js and npm, and installing them failed:\n\n${log.readText().takeLast(2000)}"
        }
    }

    /**
     * Writes the DNS preload if it is missing.
     *
     * Called before every launch, not just on install: the launcher always passes --preload, and a
     * sandbox provisioned by an older build would otherwise point the CLI at a file that is not
     * there.
     */
    fun ensureDnsPreload(rootfs: File) {
        runCatching {
            File(rootfs, DNS_PRELOAD.removePrefix("/")).apply {
                if (isFile) return@runCatching
                parentFile?.mkdirs()
                writeText(
                    """
                    try { require("dns").setServers(["1.1.1.1", "8.8.8.8"]); } catch (e) {}
                    """.trimIndent()
                        +
                        "\n",
                )
            }
        }
    }

    /** Upgrades an already-installed Claude Code package in place. */
    fun updateIn(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        timeoutMinutes: Long = 15,
    ) {
        val log =
            File(runtimeDirectory, "logs/claude-update.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val exitCode = runInRootfs(UPDATE_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        if (exitCode != 0) {
            collectDiagnostics(rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        }
        check(exitCode == 0) { failureMessage("update", exitCode, log) }
        ensureDnsPreload(rootfs)
    }

    fun isInstalledIn(rootfs: File): Boolean = File(rootfs, CLAUDE_BINARY.removePrefix("/")).isFile

    private fun runInRootfs(
        script: String,
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        log: File,
        timeoutMinutes: Long,
        append: Boolean = false,
    ): Int {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val process =
            ProcessBuilder(
                listOf(
                    suite.proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    rootfs.absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "/sys",
                    "-b",
                    "/system",
                    "-w",
                    "/root",
                    // Deliberately not a login shell: the script carries its own environment, and a
                    // login shell would re-read /etc/profile, narrowing PATH to the OpenCode set.
                    "/bin/sh",
                    "-c",
                    script,
                ),
            ).redirectErrorStream(true)
                .redirectOutput(
                    if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log),
                )
                .apply {
                    environment().clear()
                    environment().putAll(localRuntimeEnvironment(suite.environment(), prootTmp))
                }
                .start()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("Claude Code package operation timed out after $timeoutMinutes minutes. $PACKAGE_INSTALL_RETRY_HINT")
        }
        return process.exitValue()
    }

    private fun collectDiagnostics(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        log: File,
        timeoutMinutes: Long,
    ) {
        runCatching {
            runInRootfs(diagnosticsScript(), rootfs, suite, runtimeDirectory, log, timeoutMinutes, append = true)
        }
    }

    private val PACKAGE_ERROR_PATTERN =
        Regex("(?i)\\b(error|failed|fatal|conflict|overwrite|missing|unauthorized|401|403|404|408|429|no space left|not found|ETIMEDOUT|ECONNREFUSED|EAI_AGAIN|network)\\b")

    internal fun failureMessage(
        operation: String,
        exitCode: Int,
        log: File,
    ): String {
        val text = log.takeIf(File::isFile)?.readText().orEmpty()
        val errors = extractPackageErrors(text)
        val head = text.take(1_500)
        val tail = text.takeLast(2_000)
        // The tail is often filled by the post-failure diagnostics, which pushes the actual npm
        // error lines (emitted early) out of view. Include the head, skipping it only when the log
        // is short enough that head and tail already overlap.
        val showHead = text.length > head.length + tail.length
        val primary =
            errors.lineSequence().firstOrNull()
                ?: tail.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
        return buildString {
            append("Claude Code ")
            append(operation)
            append(" failed (exit ")
            append(exitCode)
            append("): ")
            append(primary)
            // On its own line right after the primary error: the errors block and the log head and
            // tail that follow run to thousands of characters, and a hint buried under them is
            // never read (issue #290).
            append('\n')
            append(PACKAGE_INSTALL_RETRY_HINT)
            if (errors.isNotBlank()) {
                append('\n')
                append(errors)
            }
            if (showHead) {
                append("\n--- log head ---\n")
                append(head)
            }
            append("\n--- log tail ---\n")
            append(tail)
        }
    }

    internal fun extractPackageErrors(text: String): String =
        text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .filter { line ->
                !line.startsWith("npm warn ") && PACKAGE_ERROR_PATTERN.containsMatchIn(line)
            }
            .take(40)
            .joinToString("\n")
}