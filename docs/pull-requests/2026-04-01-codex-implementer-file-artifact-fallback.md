<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: recover implementer artifacts from workspace writes

## Overview

Add an implementer-only fallback that synthesizes a code artifact from files written in the agent working directory when
the MCP `submit_code_artifact` tool is never called before the Claude session exits.

## Motivation

The implementer currently treats `artifact.edn` as mandatory. In practice, the agent can exhaust its turn budget after
successfully writing files into the workspace but before calling the MCP submit tool, which causes the phase to fail and
discard recoverable work.

## Changes in Detail

- Added `ai.miniforge.agent.file-artifacts` to snapshot the working tree before and after the implementer session, diff
  new and modified files, and synthesize a `:code/files` artifact when needed.
- Threaded a pre-session snapshot through `artifact-session` so implementer sessions can recover only files attributable
  to the current run.
- Updated the implementer to prefer the MCP artifact, fall back to collected workspace files, and log
  `:implementer/file-artifact-fallback` when recovery is used.
- Softened the implementer prompt so MCP submit remains preferred, while clarifying that workspace writes are captured
  automatically if the session runs out of turns.
- Added focused tests for the snapshot collector, session snapshot threading, and implementer fallback logging/success
  behavior.

## Scope Decision

This PR keeps the fallback implementer-only.

- `implementer` already has a workspace-write contract, so reconstructing `:code/files` from disk matches its output
  semantics.
- `tester` produces a logical test artifact and currently instructs the agent not to write files directly, so broadening
  fallback there would change behavior rather than just recover it.
- `releaser` emits metadata, not workspace file output, so a working-tree scan is not a faithful recovery path.

## Testing Plan

- `bb test`

## Deployment Plan

No special deployment steps. The change is internal to agent artifact handling and is safe to release with the normal
application rollout.

## Related Issues/PRs

- Base branch: `main`
- Depends on: none

## Checklist

- [x] Snapshot pre/post working tree state for implementer sessions
- [x] Recover code artifacts from written files when MCP submit is missing
- [x] Preserve MCP submit as the preferred path
- [x] Keep unrelated prompt hard-limit work out of this PR
- [x] Verify the changed `agent` brick tests with `bb test`
