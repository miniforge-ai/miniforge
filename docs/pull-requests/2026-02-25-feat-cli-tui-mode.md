# feat/cli-tui-mode — PR3a

## Layer

Application (CLI)

## Depends on

- None (independent)

## Overview

Adds a `--tui` flag to `mf run` that launches the TUI monitoring dashboard
alongside the workflow execution, giving operators a real-time view of pipeline
progress.

## Motivation

Running `mf run` currently produces log output but no interactive feedback.
The TUI monitoring mode lets operators watch workflow phases execute in real
time, see event streams, and (in future PRs) pause/resume/cancel from the
dashboard. This is the entry point for the meta-loop TUI monitoring seat.

## Changes In Detail

| File | What changed |
|------|-------------|
| `bases/cli/src/ai/miniforge/cli/main/commands/run.clj` | Added `--tui` CLI flag; `run-with-tui` fn creates shared event-stream, launches workflow on background thread via `future`, blocks main thread on `start-tui!` |
| `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` | Accepts external event-stream via opts map instead of always creating its own |
| `bases/cli/src/ai/miniforge/cli/main.clj` | Registered `--tui` flag in CLI spec |

**+41 LOC** across 3 files.

## Testing Plan

- [ ] Manual smoke test: `mf run --tui` with a sample workflow
- [ ] Pre-commit hooks pass (lint, format, test, graalvm)
- [ ] Verify TUI launches on main thread and workflow runs on background thread
- [ ] Verify graceful shutdown when TUI exits

## Deployment Plan

Additive CLI flag — no impact on existing `mf run` behavior without `--tui`.
Ships with next CLI release.

## Related Issues / PRs

- Independent — no blockers or dependents in Wave 1
- Part of the meta-loop TUI monitoring seat

## Checklist

- [x] No breaking changes to existing CLI behavior
- [x] Polylith interface boundaries respected
- [x] Babashka compatible
