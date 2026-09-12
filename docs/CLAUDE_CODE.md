# Claude Code on Android

Claude Code is a second local runtime target. It reuses the Debian Linux rootfs, PRoot launcher,
`/workspace` bind mount, command environment and logs that the local OpenCode runtime already
installs. The APK does not contain or redistribute a Claude binary.

## Agents are selectable

The Debian sandbox and PRoot launcher are shared, but the agents inside it are not. `LocalRuntimeMetadata.components`
records which of `opencode` / `claude-code` is provisioned, and `LocalRuntimeInstaller.install`
downloads the OpenCode binary only when OpenCode is among the requested agents — so a Claude
Code-only setup skips a download it would never use. Installing one agent later never removes the
other: requested agents are unioned with what is already recorded, and `/root` (which holds every
agent's credentials) is carried across the staging swap.

The setup guide asks for this choice in its first step; the Workspaces screen can add either agent
afterwards.

## Installation

Anthropic publishes Claude Code on npm, so the app installs the official package into the existing
rootfs with the sandbox's own `npm`:

```sh
/usr/bin/npm install -g --prefix /usr/local --no-fund --no-audit @anthropic-ai/claude-code@latest
/usr/local/bin/claude --version
```

Two details are load-bearing:

- **`npm` is a strict permission-solver.** A failed install is reported as-is (the registry error
  lines, deduplicated by `ClaudeCodeInstaller.extractPackageErrors`) and retried, rather than swept
  under an exit code the way apk's broken-package accounting was. There is no package database that
  can be poisoned by PRoot's hard-link emulation, so `npm` either completes or names what happened.
- **`--prefix /usr/local` fixes the binary path.** Without it npm would place the package under
  `/usr/local/lib/node_modules` whose `bin` entries fall outside the sandbox's `PATH` contract, and
  the launcher would not find `claude`. `ClaudeCodeInstaller.CLAUDE_BINARY` is the single source of
  truth for the path (it is what `ClaudeSandboxLauncher`, MCP and sign-in all use).

Updates re-run the same global install: npm is idempotent on an up-to-date package, and the `@latest`
pinned channel means an update always resolves against the current release. Node.js and npm are part
of the shared toolchain (`nodejs`, `npm` via apt when Claude Code is requested); existing minimal
runtimes that predate that get them installed on demand. `USE_BUILTIN_RIPGREP=0` is set because the
bundled ripgrep is built for the host's architecture/features and the sandbox provides Debian's
ripgrep instead.

### A failed transaction leaves nothing behind

With apk, a package broken by the extractor kept a flag that poisoned every later transaction. npm
keeps no such state: the global install either completes, or its exit code and the registry error
lines are reported and the operation is retried.

The script therefore has no reason to paper over a non-zero exit. It verifies what was asked for with
`claude --version` at the end, and only that check failing (or the binary not appearing at
`/usr/local/bin/claude`) is reported as a failure — the diagnostics append `npm list -g --depth=0`
and a `df -h /` so the state of the sandbox is visible in the log tail.

### The card reports which version an update landed on

`npm` re-installs the package and reports nothing about the version, so an update that had nothing to
do and one that installed a new build were indistinguishable — the button stopped spinning either way.
`ClaudeCodeTarget.update` therefore reads `claude --version` on both sides of the upgrade and returns
a `ClaudeUpdateResult`: `Updated(from, to)` when the version moved, `AlreadyLatest(version)` when it
did not. The card shows the installed version next to the update button and the outcome underneath.

"Already up to date" is read from the sandbox, not assumed: the command's own version check runs
inside the guest immediately after the idempotent `npm install` runs, so a version that did not move
means the registry had nothing newer (or the same `@latest` resolved again).

## Execution

Prompts run through Claude Code's streaming-JSON protocol rather than its terminal UI:

```text
claude --print --input-format stream-json --output-format stream-json --verbose \
       --include-partial-messages --permission-mode <mode> \
       (--session-id <uuid> | --resume <claude-session-id>)
```

One process stays alive per chat session and exchanges newline-delimited JSON over stdin/stdout.
`system`, `assistant`, `user`, `stream_event` and `result` messages are mapped onto the existing chat
event model, so assistant text, reasoning, tool calls, tool results and streaming deltas render in
the normal UI. Conversation state lives in Claude Code's own session store, so a process that exits
is relaunched with `--resume` and history survives.

The interactive TUI is deliberately not scraped: it is a full-screen renderer whose output has no
stable line structure, and the heuristics needed to read it misfire on ordinary assistant prose.

## Permissions

Per-tool approvals use Claude Code's **PermissionRequest hook** bridged to the Android approval UI
(chat chip + notification). AndCode installs `and-code-claude-permission-hook.sh` into the guest and
merges a `PermissionRequest` handler into `~/.claude/settings.json`. The hook writes a request under
`/root/.andcode/claude-bridge` (bind-mounted from the app runtime directory); the app responds with
allow/deny JSON the hook is polling for.

| Mode | CLI value | Effect |
| --- | --- | --- |
| Plan only | `plan` | Reads and plans; never edits or runs commands |
| Ask each time | `default` | Unmatched tools prompt in AndCode (requires the hook bridge) |
| Accept edits (default) | `acceptEdits` | Auto file ops; Bash pre-approved via `--allowedTools` |
| Full access | `bypassPermissions` | Runs any command without asking |

If the hook is missing (older install not yet re-provisioned), Ask mode falls back to Accept edits
for that process so the CLI cannot hang. `ClaudeCodeTarget.capabilities.permissions` / `questions`
are true only when the bridge is installed. "Always allow" stores a rule in the bridge's
`always-rules.json` so matching tools skip the UI on later turns.

See `docs/superpowers/specs/2026-08-08-claude-code-opencode-local-parity-design.md`.

## Sign-in

`claude auth login` is interactive: it prints an authorization URL, waits for browser approval, then
reads back the code the browser shows. There is no browser inside PRoot, so the app plays that role.
`ClaudeAuthCoordinator` runs the command under a pseudo-terminal (Debian `bsdutils`'s `script`,
required because the CLI only prints a pasteable URL when it believes a human is watching), captures
the URL, hands it to Android via `ACTION_VIEW`, and writes the pasted code back to the CLI's stdin.
Success is confirmed against `claude auth status`, not inferred from the exit code.

The app never creates, stores or handles OAuth tokens itself — credentials stay in Claude Code's own
store under `/root` inside the sandbox.

## History and Events

Session metadata and normalized Claude messages are stored in the app-private runtime directory.
Writes are coalesced to turn boundaries rather than issued per streamed line.
