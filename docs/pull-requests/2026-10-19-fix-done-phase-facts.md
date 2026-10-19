<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Establish terminal phase completion facts

## Purpose and layer

Phase lifecycle integration: the native `:done` interceptor owns its completion
state. It produces no artifacts, uses no model tokens, and reports measured
terminal timing. It cannot inherit a previous phase's errors, transition, receipts,
or metrics. Previous recorded phase results remain unchanged.

## Dependencies and scope

Both prerequisites, #2056 and #2054, are merged. Aligned with main without
changing the tested production or test sources. The actual runner already
clears the active phase before entering done; there is no prior output-forwarding
contract to change. Leave/error compatibility remains unchanged. Default current
host migration and workflow-level lifecycle publication remain separate work.

## Verification plan

Unit coverage for owned facts and direct-interceptor isolation; actual runner with
current journal and durable reopen; all affected product consumers; rebuilt CLI
with isolated package tests. Normal signed commits, adversarial standards review,
exact-head review and all CI gates. No storage migration; normal CLI deployment.

## Local verification and adversarial review

- Core compatibility and actual runner/current journal: 9 tests, 47 assertions.
- All three products consuming phase/workflow/phase-opsv: passing, serially (1m56s).
- Rebuilt CLI: 39,187,015 bytes; isolated package: 34 tests, 279 assertions,
  including publication refusals, native finalization and terminal recovery.
- Kondo: no warnings/errors. Inferred strata: no changes required.
- Reviewed state ownership, absent versus empty receipts, unchanged leave/error
  behavior, preserved phase history and shared metrics. Aligned timestamps with
  the existing millisecond representation; no copied prior output or diagnostics.

This verifies phase events, not the default host or workflow-level lifecycle.
Both dependency review rounds are integrated and covered by the composed checks,
including partial terminal receipts, event refusal and consecutive bundle retries.
