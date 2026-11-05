<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(agent): split artifact_session.clj into four namespaces (rule 210)

## Overview

Mechanical rule 210 split of `components/agent/src/ai/miniforge/agent/artifact_session.clj`
into four namespaces, each within the 3-layer budget. No behavior change.

## Motivation

The file spans 8 call-graph layers against a budget of 3. Any edit to it arms
stratum-lint, which then fails SL003. The agent tool hook settings this file writes
need changes, and those changes should pass stratum-lint without an opt-out.

## Changes in Detail

1. `artifact-session.cli-config`: the miniforge and MCP server commands, the Claude
   PreToolUse settings, the Codex MCP block, the Cursor MCP config and permission
   allowlist, and their cleanup.
2. `artifact-session.parsing`: UUID and instant strings in artifact EDN, the worktree
   role files, the answer log, and the capsule output markers.
3. `artifact-session.readers`: reading the MCP artifact, worktree role artifacts,
   context misses and reads, and peg answers, on the host and in a capsule.
4. `artifact-session`: the session lifecycle and the `with-session` runners, plus 28
   one-line aliases that keep each public name a caller uses.

All 73 definitions move unchanged. The edits are cross-namespace qualification,
public visibility for 13 definitions another namespace now reaches, the aliases, and
the namespace docstring. `create-session!`, `write-mcp-config!`,
`create-capsule-session!` and `read-host-session-outputs` stay in the core namespace,
and core calls every aliased definition through its alias, as main called those vars,
so a test that redefines `artifact-session/read-artifact` and friends still steers
`with-session`, `with-readonly-session` and both macros. Three moved public
definitions that nothing outside the split uses get no alias (no-dead-code).

Limits of the aliases: they do not carry `:arglists` (no clj-kondo arity check through
the alias name), and reloading only a sub-namespace at a REPL leaves an alias holding
the old function until `artifact-session` reloads.

Tests that reach moved internals follow them: the command test redefines
`cli-config/command-on-path?`; `artifact-session-test` reads
`cursor-permissions-backup-suffix` from `cli-config`. Two tests resolve the capsule
output markers by name in `artifact-session`; aliases for those markers keep that
working and leave `artifact_session_capsule_test.clj`, already over the layer budget
on main, untouched.

## Testing Plan

1. Form-by-form comparison of every definition against main, after normalizing
   stratum numbers, `defn-`/`^:private` and namespace prefixes: 0 differences.
2. stratum-lint on the four files: pass. clj-kondo: no errors or warnings.
3. Every agent component test namespace, run under the shared JVM lock. The first
   run caught a with-redefs regression, fixed by keeping `read-host-session-outputs`
   in core; a scan of every `with-redefs`, `binding` and `alter-var-root` vector in
   the repo found no other redefinition of a moved var.
4. Independent adversarial review of the split.

## Deployment Plan

Ships with the next release; no configuration or data change.

## Related Issues/PRs

1. Precedent for an atomic split with budget overrides: #2093 (semantic-analyzer).
2. Unblocks the default-deny tool hook (#2251) and the follow-up path checks, which
   edit the settings this file writes.

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [ ] Agent test namespaces pass
- [x] Independent review settled (alias routing and docstring fixed; `:arglists` noted)
- [ ] CI green, Copilot comments resolved
