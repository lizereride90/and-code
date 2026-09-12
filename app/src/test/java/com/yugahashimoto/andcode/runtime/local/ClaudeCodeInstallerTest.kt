package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeCodeInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `extractPackageErrors keeps ERROR and WARNING lines`() {
        val log =
            """
            npm notice
            npm ERR! code EAI_AGAIN
            npm ERR! network request to https://registry.npmjs.org/@anthropic-ai%2fclaude-code failed
            WARNING: read-only file system, skipping lint
            """.trimIndent()
        val errors = ClaudeCodeInstaller.extractPackageErrors(log)
        assertTrue(errors.contains("npm ERR! code EAI_AGAIN"))
        assertTrue(errors.contains("npm ERR! network request"))
    }

    @Test
    fun `extractPackageErrors drops npm warn and progress lines even when they contain a keyword`() {
        val log =
            """
            npm warn deprecated something@1.0.0: no longer maintained
            npm notice 10.9.4
            npm ERR! code ETIMEDOUT
            """.trimIndent()
        val errors = ClaudeCodeInstaller.extractPackageErrors(log)
        assertFalse(errors.contains("npm warn "))
        assertFalse(errors.contains("npm notice"))
        assertTrue(errors.contains("npm ERR! code ETIMEDOUT"))
    }

    @Test
    fun `extractPackageErrors returns empty when nothing matches`() {
        assertEquals("", ClaudeCodeInstaller.extractPackageErrors("added 1 package in 8s\n"))
    }

    @Test
    fun `failureMessage puts the primary error on the first line so compact views show it`() {
        val log =
            File.createTempFile("claude-install", ".log").apply {
                deleteOnExit()
                writeText("npm ERR! code E403\nnpm ERR! 403 Forbidden - PUT https://registry.npmjs.org/@anthropic-ai%2fclaude-code\n")
            }
        val message = ClaudeCodeInstaller.failureMessage("installation", 1, log)
        val firstLine = message.lineSequence().first()
        // `E403` is not itself matched by the error pattern (no word boundary before the digits),
        // so the primary error is the following `npm ERR! 403 Forbidden - PUT ...` line that is.
        assertTrue(
            firstLine.startsWith("Claude Code installation failed (exit 1): npm ERR! 403 Forbidden"),
        )
        assertTrue(message.contains("--- log tail ---"))
    }

    @Test
    fun `failureMessage prefers a real error over the npm notice banner`() {
        val log =
            File.createTempFile("claude-update", ".log").apply {
                deleteOnExit()
                writeText(
                    "npm notice 10.9.4\nnpm notice New minor version of npm available! 10.2.4 -> 10.9.4\nnpm ERR! code EAI_AGAIN\nnpm ERR! syscall getaddrinfo\n",
                )
            }
        val message = ClaudeCodeInstaller.failureMessage("update", 1, log)
        assertTrue(
            message.lineSequence().first().endsWith("npm ERR! code EAI_AGAIN"),
        )
    }

    @Test
    fun `failureMessage carries the network retry hint before the log dump`() {
        // Issue #290: the raw npm error reads like a broken build when it is usually just a
        // timed-out download, and a hint buried under the log tail is never read.
        val log =
            File.createTempFile("claude-install-hint", ".log").apply {
                deleteOnExit()
                writeText("npm ERR! network request to https://registry.npmjs.org failed\n")
            }
        val message = ClaudeCodeInstaller.failureMessage("installation", 1, log)
        val lines = message.lineSequence().toList()
        assertTrue(lines.indexOfFirst { it.contains(PACKAGE_INSTALL_RETRY_HINT) } > 0)
        assertTrue(
            lines.indexOfFirst { it.contains(PACKAGE_INSTALL_RETRY_HINT) } <
                lines.indexOfFirst { it.contains("--- log tail ---") },
        )
    }

    @Test
    fun `assembled scripts abort on the first failure and end with the package work`() {
        listOf(ClaudeCodeInstaller.INSTALL_SCRIPT, ClaudeCodeInstaller.UPDATE_SCRIPT).forEach { script ->
            assertEquals("set -e", script.lineSequence().first())
            assertEquals("${ClaudeCodeInstaller.CLAUDE_BINARY} --version", script.lineSequence().last())
            assertTrue(script.contains("npm install -g --prefix /usr/local"))
        }
    }

    @Test
    fun `both scripts target the shared npm latest channel under the global prefix`() {
        listOf(ClaudeCodeInstaller.INSTALL_SCRIPT, ClaudeCodeInstaller.UPDATE_SCRIPT).forEach { script ->
            assertTrue(script.contains("@anthropic-ai/claude-code@latest"))
            // npm only installs into Prefix when the flag precedes the package, so the ordering is
            // part of the contract the sandbox relies on.
            val prefix = script.indexOf("--prefix /usr/local")
            val packageArg = script.indexOf("@anthropic-ai/claude-code")
            assertTrue(prefix in 0 until packageArg)
        }
    }

    @Test
    fun `node cannot be assumed to exist so the runtime probe drives an apt fallback`() {
        val commands = ClaudeCodeInstaller.ensureNodeRuntimeCommands("/usr/bin/node")
        assertTrue(commands.contains("/usr/bin/node -e '' 2>/dev/null && exit 0"))
        assertTrue(commands.contains("apt-get install -y -qq --no-install-recommends nodejs npm"))
    }

    @Test
    fun `install reports success when npm installs the package`() {
        val run = runPackageCommands(ClaudeCodeInstaller::installPackageCommands)

        assertEquals(0, run.exitCode)
        assertTrue(run.claudeRan)
        assertTrue(run.npmInvocations.first().startsWith("install -g "))
    }

    @Test
    fun `install fails when npm cannot be reached`() {
        val run =
            runPackageCommands(ClaudeCodeInstaller::installPackageCommands, npmExitCode = 1)

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("npm failed to install"))
    }

    @Test
    fun `update is a no-op when npm has nothing to do`() {
        val run = runPackageCommands(ClaudeCodeInstaller::updatePackageCommands)

        assertEquals(0, run.exitCode)
        assertTrue(run.claudeRan)
    }

    @Test
    fun `update fails when the installed binary cannot run`() {
        val run =
            runPackageCommands(ClaudeCodeInstaller::updatePackageCommands, claudeExitCode = 1)

        assertNotEquals(0, run.exitCode)
    }

    private class ScriptRun(val exitCode: Int, val output: String, val npmInvocations: List<String>) {
        val claudeRan: Boolean get() = output.contains(CLAUDE_VERSION)
    }

    /**
     * Runs the package-manager half of a script against stub `npm` and `claude` binaries.
     *
     * The stubs report whatever they are told to, which is the only way to exercise the branches
     * that matter here: npm failing for registry reasons that have nothing to do with claude.
     */
    private fun runPackageCommands(
        commands: (String, String) -> String,
        claudeExitCode: Int = 0,
        npmExitCode: Int = 0,
    ): ScriptRun {
        val bin = temporaryFolder.newFolder("bin")
        val npm =
            stub(
                bin,
                "npm",
                """
                #!/bin/sh
                echo "${'$'}*" >> '@LOG@'
                exit $npmExitCode
                """.replace("@LOG@", File(bin, "npm.log").absolutePath),
            )
        val claude =
            stub(
                bin,
                "claude",
                """
                #!/bin/sh
                echo '$CLAUDE_VERSION'
                exit $claudeExitCode
                """.trimIndent(),
            )
        val process =
            ProcessBuilder("sh", "-c", "set -e\n" + commands(npm.absolutePath, claude.absolutePath))
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("stub script timed out", process.waitFor(60, TimeUnit.SECONDS))
        return ScriptRun(
            exitCode = process.exitValue(),
            output = output,
            npmInvocations =
                File(bin, "npm.log").takeIf(File::isFile)?.readLines().orEmpty(),
        )
    }

    private fun stub(
        directory: File,
        name: String,
        body: String,
    ): File =
        File(directory, name).apply {
            writeText(body)
            check(setExecutable(true))
        }

    private companion object {
        const val CLAUDE_VERSION = "2.1.212 (Claude Code)"
    }
}