<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent): refuse writes to the real target of a linked config path

## Overview

The tool hook refuses agent writes to the config files a later session loads
(`.claude/settings.json`, `.mcp.json`, and the rest of `:tool/protected-paths`). When one
of those paths is a symlink, a write to the file it points at changed the same
settings and passed. The hook now also checks each path under the managed config
paths once they are resolved.

## Motivation

Copilot review on #2271: with `.claude` linked to `shared-config/`, a write to
`shared-config/settings.local.json` passed. Neither the canonical nor the as-written
form of that path names `.claude/`, and the worktree guard sees no symlink on it, but
Claude loads the file as `.claude/settings.local.json`.

## Changes in Detail

1. `tool-policy.edn` `:tool/managed-config-paths`: the protected config paths as
   concrete paths (directories and files), added to the closed schema.
2. `tool-policy/follow-links` resolves each symlink on a managed path hop by hop,
   whether its target exists or not (`getCanonicalFile` stops at a dangling link and
   returns the link's own path), walking each link target one component at a time so
   a `..` applies after the links before it, as the kernel resolves it.
   `managed-config-aliases`: for each managed path whose resolved form is, or contains,
   the canonical form of the path a tool names, that managed path plus the rest of the
   path below it.
3. `path-refusal` refuses a path when any of those names matches
   `:tool/protected-paths`.

## Testing Plan

1. `linked-config-target-stays-protected-test`: with `.claude` linked to a directory
   and `.mcp.json` linked to a file, writes to `shared-config/settings.local.json` and
   to the linked file are refused (both fail on #2271's branch); other files in the
   linked directory stay writable; links whose targets do not exist yet
   (`.codex -> missing-dir`, `opencode.json -> cfg/opencode.json`) are followed; a
   relative `context_write` path is refused like an absolute one; with
   `.claude -> alias/../cfg` and `alias -> nested/dir`, a write to the file Claude loads,
   `nested/cfg/settings.json`, is refused (Copilot review: a lexical `..` pointed the
   check at `cfg/`).
2. `every-protected-path-is-a-managed-path-test`: each `:tool/protected-paths` pattern
   matches a managed path, so the two lists cannot drift apart unnoticed. The schema
   test covers a policy missing the new key.
3. Agent tool-policy, supervisor and hook tests pass.
4. Independent adversarial review; findings fixed or listed below.

## Known limits

1. An agent with Bash can replace a config path with a link after writing the
   target (`ln -s shared .claude`). Bash can already copy a file into `.claude/`;
   the Bash patterns are defense in depth, not the control for this.

## Deployment Plan

Ships with the next release, after #2271.

## Related Issues/PRs

1. Stacked on #2271 (tool hook path confinement).

## Checklist

- [x] Tests fail without the change and pass with it
- [ ] CI green, Copilot comments resolved
