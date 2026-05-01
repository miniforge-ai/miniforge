# feat: Codex tool parity — explicit sandbox/approval + required MCP server

## Overview

Follow-up to [PR #729](https://github.com/miniforge-ai/miniforge/pull/729). Closes the parity gap with Claude's
`--allowedTools` / `--mcp-config` semantics that left Codex running with no tool-surface lock-down — relying on prompt
instructions alone to keep the agent inside our intended capability surface.

## Motivation

The PR #729 dogfood verified that the implementer prompt's tool-first delivery framing dramatically improved Codex's
behavior, but Codex still ran with permissive defaults: `--full-auto` set the sandbox but nothing forced our artifact
MCP server to be loaded, and any default in `~/.codex/config.toml` could override the approval policy. The earlier
dogfood saw 1 of 5 runs trip a planner flake (15.7-min plan-phase no-op) where the agent presumably ran into approval
friction with no clear failure mode.

Reading the Codex config-reference established the actual shape of the problem:

- Codex has **no per-built-in-tool allow-list**. `apply_patch` and `shell` are governed by `sandbox_mode` categorically,
  not toggleable individually. There is no equivalent to Claude's `--allowedTools`.
- `approval_policy` is the human-in-the-loop control.
- `mcp_servers.<id>.required = true` is the "fail loudly if my server can't load" lever — the closest Codex analog to
  Claude's behavior when `--mcp-config` can't initialize.
- `features.shell_tool = false` can disable Codex's default shell tool, but doing so is risky without a clear MCP
  replacement; deferring.

So the actionable parity work is small: move from the deprecated `--full-auto` alias to its explicit components, force
our MCP server to be required, and pin the approval policy at both layers.

## Changes In Detail

### 1. `codex-args` — explicit flags

`components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj`

Replaces `--full-auto` (deprecated alias) with its constituents and adds two `-c` config overrides:

```clojure
;; Before:
["exec" "--json" "--full-auto" "--skip-git-repo-check"]

;; After:
["exec" "--json"
 "--sandbox=workspace-write"
 "--ask-for-approval=never"
 "--skip-git-repo-check"
 "-c" "approval_policy=\"never\""
 "-c" "mcp_servers.artifact.required=true"]
```

- `--sandbox=workspace-write` — explicit; agent can edit files in the workspace.
- `--ask-for-approval=never` — no human prompts.
- `-c approval_policy="never"` — same intent at the TOML layer so it survives any user-level config defaults.
- `-c mcp_servers.artifact.required=true` — Codex startup fails if our MCP server can't initialize.

The function's docstring documents the constraints: what Codex's tool model lets us control, what it doesn't, and where
the implementer prompt picks up the slack.

### 2. `write-codex-mcp-config!` — `required = true` in TOML

`components/agent/src/ai/miniforge/agent/artifact_session.clj`

The artifact block written to `~/.codex/config.toml` now includes `required = true`:

```toml
[mcp_servers.artifact]
command = "..."
args = [...]
required = true
```

Defense in depth: if the CLI `-c` override is ever stripped by a future refactor, the persistent config remains
authoritative.

### 3. Tests

- `codex-args-minimal-test` — asserts the new explicit flags replace `--full-auto`.
- `codex-args-mcp-required-test` — asserts the two new `-c` overrides are present.
- `write-codex-mcp-config-test` — asserts `required = true` lands in the TOML block.

## What we cannot do (and don't try to in this PR)

- **Allow-list individual built-in tools.** Codex doesn't expose that knob; `apply_patch` will always be available in
  workspace-write mode. The implementer prompt remains the primary lever for steering toward intended tools.
- **Force the artifact MCP server's tools to be exclusive.** The `mcp_servers.artifact.enabled_tools` allow-list IS
  available in Codex config, but threading it through cleanly intersects with the `:context` vs `:artifact`
  MCP-server-naming inconsistency in `components/agent` (the canonical name is `:context` in `mcp-tools` data; the Codex
  TOML block names it `artifact`). That cleanup deserves its own PR.

## Verification

Pre-commit passes. The runtime change should be observable on the next dogfood: a misconfigured artifact MCP server now
produces a Codex startup error rather than a silent run with the server absent.

## Followups

- `mcp_servers.artifact.enabled_tools` allow-list once the `:context` ↔ `:artifact` server-naming is unified.
- `features.shell_tool = false` once an MCP-side shell replacement exists. Probably deferred until the per-task base
  chaining work (PR #730 spec) lands and we have a fuller picture of which built-in tools are actually load-bearing.
