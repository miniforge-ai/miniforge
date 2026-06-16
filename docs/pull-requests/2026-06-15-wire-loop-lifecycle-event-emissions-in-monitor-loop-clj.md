<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Wire loop lifecycle event emissions in monitor_loop.clj.

**PR:** [#1189](https://github.com/miniforge-ai/miniforge/pull/1189)
**Branch:** `mf/wire-loop-lifecycle-event-emissions-in-m-e3b53859`

## Summary

Wire loop lifecycle event emissions in monitor_loop.clj.

## Files Changed

- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_loop.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

All three emission sites are wired correctly. loop-started fires after log-loop-start! and before step-monitor-loop!. loop-stopped fires after step-monitor-loop! returns, with (get @monitor :stop-reason :completed) covering no-open-prs, manual-stop, and the natural-completion default in a single emission point. cycle-completed with nil pr-number fires after the swap! in finalize-loop-iteration!, correctly distinguished from per-PR cycle-completed events by the nil field. The stop-reason writes are complete: :no-open-prs in stop-when-no-open-prs!, :manual-stop in stop-monitor-loop. Verification findings are accurate: event-type-registry exists as an SSE/browser layer (inapplicable here, existing constructors already registered in monitor-events), and the event-bus is confirmed as a side bus (follow-up required but correctly deferred). One in-scope warning: swap! return value should be captured rather than re-dereferencing @monitor. No blocking in-scope issues.

### Known issues (non-blocking)

Merged with 2 unresolved non-blocking issue(s) recorded for follow-up:

- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_loop.clj:163` — TOCTOU: `swap!` return value is discarded; `:cycles` is then read from a second `@monitor` dereference. `(swap! monitor ...)` returns the new state map atomically — reading `(:cycles @monitor)` after it is a separate, non-atomic read. Another writer could swap the atom between the two calls. In this component's single-threaded loop this is practically safe, but the pattern is wrong and will silently emit a stale iteration count if concurrency is ever introduced.
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_loop.clj:20` — Rule 009 (self-documenting-code): The VERIFICATION FINDINGS comment block narrates investigation reasoning — bus identity, registry lookup, conclusions. Per rule 009, source files describe the current contract; investigation narrative belongs in the PR doc or a follow-up issue, not the source. 'Never narrate your own reasoning in the code. The diff and PR description are for that.' The spec said document findings; the right location is the PR doc, not a code comment block.
