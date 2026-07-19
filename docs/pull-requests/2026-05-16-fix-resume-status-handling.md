<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Fix: workflow resume fails loud on non-terminal status + honest "Resuming from phase" line

## Overview

Resolves the silent fast-fail blocker from the 2026-05-16
event-log-tool-visibility dogfood
(`work/workflow-resume-status-handling.spec.edn`). `bb miniforge resume
<id>` used to print "Resumed workflow completed with status: :running"
and exit `0`, silently losing the prior session's
plan/explore/verify token spend. It also printed a misleading "Resuming
from phase: explore" even when the FSM snapshot was parked at `verify`.

This is a CLI-shell fix: make the contract honest. The deeper "why
doesn't `run-pipeline` advance the FSM from a snapshot" question is
captured separately — that lives in the workflow / FSM layer and needs
its own design pass.

## Motivation

Two consecutive resumes from the 2026-05-16 dogfood produced:

```text
Resuming workflow: c1abda63-3072-4f57-9871-6c177384968e
Completed phases: verify
Events found: 163
Restored workflow ID: c1abda63-3072-4f57-9871-6c177384968e
Resuming from phase: explore (8 phases remaining)
Resumed workflow completed with status: :running
```

Process exited 0. The operator had no signal that resume had failed
beyond reading the events log by hand. For a dogfood loop where the
whole point of resume is to avoid burning plan-phase tokens twice,
this turns into "spend $2 on plan, get told 'completed', then notice
nothing happened, restart from scratch, spend another $2."

## Changes in Detail

### Fail-loud on non-terminal status

`bases/cli/src/ai/miniforge/cli/main/commands/resume.clj` —

- New `terminal-statuses` set: `{:completed :completed-with-warnings
  :failed :aborted :cancelled}`.
- New `terminal-status?` predicate.
- After `run-pipeline` returns, check the status against the set.
  Non-terminal → `response/throw-anomaly!` with
  `:anomalies.workflow/resume-non-terminal`, the workflow id, and the
  observed status. The CLI's outer `catch Exception` re-throws so
  `main` exits non-zero.

The message points the operator at the events log for the underlying
restore-failed / FSM-stall reason rather than swallowing it.

### Honest "Resuming from phase" print

When a machine snapshot is present, the print now uses
`(:execution/current-phase machine-snapshot)` — i.e., the phase the FSM
actually parked at — instead of `(first remaining-pipeline)`, which is
just the first entry of the full untrimmed pipeline. Pipeline-trim
resumes (no snapshot) still fall back to the head of the trimmed
pipeline.

Extracted as `resume-print-phase` so the lookup is a single unit-tested
function.

### Tests

`bases/cli/test/ai/miniforge/cli/main/commands/resume_test.clj` —

- `terminal-status-predicate-test` — every member of the terminal set
  passes; `:running`, `:pending`, `:paused`, nil all fail.
- `resume-workflow-non-terminal-status-throws-test` — stubs
  `run-pipeline` to return `{:execution/status :running}` and asserts
  resume throws an ex-info naming the non-terminal status.
- `resume-print-phase-prefers-fsm-snapshot-test` — snapshot's
  `:execution/current-phase` wins over pipeline head; nil snapshot
  falls back; empty inputs return nil.

## What this does NOT fix

The underlying question — why does `run-pipeline` return `:running`
when handed a snapshot it can't advance from? — is a separate workflow
/ FSM bug. Suspect path: `assemble-initial-context` →
`ctx/restore-context` reconstructs the FSM state, but
`execute-pipeline-loop` either exits early because `terminal-state?`
gates differently than expected, or `execute-single-iteration` can't
find an interceptor matching the restored `:execution/current-phase`.
Worth filing a follow-up spec under `dogfood-resilience` once this PR
lands.

For now, the operator at least sees the failure loud and clear instead
of believing the run succeeded.

## Testing Plan

- [x] `clojure -A:test:dev -M -e "(require 'clojure.test
  '[ai.miniforge.cli.main.commands.resume-test]) (clojure.test/run-tests
  'ai.miniforge.cli.main.commands.resume-test)"`
  → 8 tests, 36 assertions, 0 failures.
- [x] `bb lint:clj` clean on touched files.
- [ ] `bb pre-commit` on the pushed branch.
- [ ] Manual: rerun `bb miniforge resume <stale-workflow-id>` — expect
  non-zero exit + diagnostic instead of silent `0` with "status: :running".

## Related

- Spec: `work/workflow-resume-status-handling.spec.edn` (filed in PR #894).
- Dogfood findings:
  `project_dogfood_findings_2026_05_16.md` — Blocker 2.
- Same shape family as PR #867 (`:passed?` gate predicate) and PR #895
  (reviewer schema flipping verdicts): strict-or-silent contracts at
  layer boundaries failing loud instead of cascading.
