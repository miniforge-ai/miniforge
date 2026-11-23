<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: capture phase-local progress reporting ports

## Scope and dependencies

Application-layer change from main after heartbeat ownership #2080. Capture the
owned activation in a transient phase-context reporting function, without changing
legacy observer callback return semantics. Late reports must never resolve the
newest handle by phase name. Checkpoints must not persist the reporting function.

This does not declare progress coverage or instrument OPSV loops. Those producers
must document and implement substantive observations before declaring coverage;
uncovered phases continue to report unknown progress.

## Verification and deployment

Test phase-context delivery, stale activation capture, legacy callback behavior,
and checkpoint exclusion. Run workflow consumers serially, rebuild the CLI and
test the isolated package. Apply adversarial standards review, normal signed
commit and PR budgets, exact-head Copilot review and every CI gate. Deploy with
the normal package after all gates pass.

All three workflow consumers pass (1m28s). The fresh CLI is 39,224,888 bytes;
isolated-package regressions pass six tests/18 assertions without production
source overlays. Real JVM test timings are 0.3–28.5ms, so these remain brick
tests. The actual-main standards scan is clean. Captured handles, callback
ordering, nil/legacy paths and checkpoint exclusion were traced adversarially.
