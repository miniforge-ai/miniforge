# fix: align model IDs in config to match model catalog

## Overview
All default config files and the CLI backends spec referenced
`claude-sonnet-4-20250514` — a date-suffixed model ID that is not registered
in `llm/model-catalog.edn`. This caused `backend-for-model` to silently fall
back to `:codex` for every execution-role lookup, routing all implementation,
verify, and review phases through Codex even when the user intended Claude.

## Motivation
Every dogfood run on the `finish-event-telemetry` spec hit Codex rate limits
on the implement phase. Investigation traced the path:

1. User/default config specifies `claude-sonnet-4-20250514` as `:execution` model
2. `default-model-for-role :implementer` returns `"claude-sonnet-4-20250514"`
3. `backend-for-model("claude-sonnet-4-20250514")` — not in catalog → returns `:codex`
4. `resolve-llm-client-for-role` creates a fresh Codex client
5. Codex rate limit hit

The catalog registers the model as `"claude-sonnet-4-6"`.

## Layer
Configuration / Infrastructure

## Base Branch
`main`

## Depends On
None.

## Changes in Detail
- `resources/config/default-user-config.edn`: execution → `claude-sonnet-4-6`,
  escalation → `claude-opus-4-6`, allowed-failover-backends `[:claude :codex]`
- `components/config/resources/config/default-user-config-fallback.edn`: same
- `bases/cli/resources/config/cli/backends.edn`: `:claude :models` updated to
  catalog IDs (`claude-sonnet-4-6`, `claude-opus-4-6`, `claude-sonnet-4-5-20250929`,
  `claude-haiku-4-5-20251001`)
- `components/phase-software-factory/resources/config/phase/defaults.edn`:
  implement/verify/review `model-hint` → `:sonnet`; plan stays `:gpt-5.4`

## Strata Affected
- `ai.miniforge.config` (shipped defaults)
- `ai.miniforge.cli` (backends spec)
- `ai.miniforge.phase-software-factory` (phase model hints)

## Testing Plan
- [ ] `(backend-for-model "claude-sonnet-4-6")` returns `:claude` in REPL
- [ ] Run `bb miniforge run work/finish-event-telemetry.spec.edn` — execution
  phases should route to Claude, not exhaust Codex

## Deployment Plan
- Merge before next dogfood run.
- Users with `claude-sonnet-4-20250514` in their personal `~/.miniforge/config.edn`
  should update to `claude-sonnet-4-6`.

## Related Issues/PRs
- PR #347 introduced the GPT-first execution defaults but used the wrong Claude
  ID string as a fallback value.

## Risks and Notes
- Any personal config files (`~/.miniforge/config.edn`) with the old date-suffixed
  ID will continue to route to Codex until updated manually.

## Checklist
- [x] Isolated onto a clean branch from `main`
- [x] Added PR doc under `docs/pull-requests/`
- [x] No code behavior change — config values only
