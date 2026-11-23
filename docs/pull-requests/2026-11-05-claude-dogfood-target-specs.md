<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore(work): add two dogfood-resilience specs and pin the resume-restore throw to its boundary

## Overview

Adds two work specs found while dogfooding on 2026-11-05 and tightens the
spec the first dogfood run targeted.

## Motivation

1. A dogfood run that throws leaves its spec in `work/in-progress/` and the
   launching checkout dirty, so the next `bb dogfood` refuses to start until
   the file is restored by hand. That happened on 2026-11-05.
2. A resumed run's event log never says it resumed: `:workflow/resumed` is
   registered, has a schema, a message and a constructor, but no producer.
3. `workflow-resume-restore-failure-escalates` asks for a new throw in
   `acquire-execution-environment!`. That function is the runner's documented
   escalation boundary, which is why its existing governed-mode throw is
   allowed by exceptions-as-data. The spec now requires the docstring to name
   the new throw too, and forbids throws anywhere else, so the policy judge
   reviewing the edited form can see the boundary.

## Changes in Detail

1. `work/spec-kanban-thrown-run.spec.edn` (new, tier high, theme
   `:dogfood-resilience`): when the run throws after `start-spec!` moved the
   spec, it is filed under `work/failed/` and the exception propagates
   unchanged.
2. `work/workflow-resume-emits-resumed-event.spec.edn` (new, tier high, theme
   `:dogfood-resilience`): an accepted resume publishes one
   `:workflow/resumed` event naming its resume phase and skipped phases.
3. `work/workflow-resume-restore-failure-escalates.spec.edn`: one constraint
   and one acceptance criterion about the docstring and the boundary.
4. `work/QUEUE.md` regenerated with `bb work:queue`.

## Testing Plan

1. Each spec reads as EDN, and `bb work:queue` renders all 83 specs.
2. Claims checked against main: `finish-spec!` runs only on the normal return
   of `execute-with-events` in `run-workflow-from-spec!`; no production code
   calls `workflow-resumed`; `:env/restore-failed` is in the workflow message
   catalog.

## Deployment Plan

Specs only; nothing to deploy.

## Related Issues/PRs

1. The first dogfood run's failure is addressed by #2410, #2411 and the
   change-scoped policy findings PR that follows them.

## Checklist

- [x] Specs carry tier, axes, theme, rationale, blocks and blocked-by (rule 021)
- [x] Acceptance criteria are testable
- [x] QUEUE.md regenerated
