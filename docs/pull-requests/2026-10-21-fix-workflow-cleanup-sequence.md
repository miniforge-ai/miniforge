<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: complete every post-workflow cleanup stage

## Scope and dependencies

Application-layer change following merged heartbeat ownership #2080 and shared
resource-cleanup foundation #2079, aligned with main. Make post-workflow cleanup attempt every stage and
both environment releases even if an earlier stage throws. Preserve primary and
fatal failure identity using the shared cleanup policy, not duplicated catch logic.

Separate signal construction and knowledge operations from cleanup orchestration
so the inferred dependency layers remain small and real. Preserve existing public
cleanup functions and ordinary knowledge-error reporting. This PR does not yet
protect the outer runner from heartbeat-stop failure before post-workflow cleanup;
that lifetime boundary is a subsequent integration step.

## Verification and deployment

Retain cleanup behavior tests and add fault-injection regressions for all-stage
execution and dual environment release. Run consuming projects serially, rebuild
and test the isolated CLI, and apply adversarial standards review. Keep normal
size limits, signed hooks, fresh exact-head review and all CI gates. No wire
contracts change; deployment uses the normal package.

All three workflow consumers passed serially (1m29s). The fresh CLI is
39,226,566 bytes; isolated package tests passed 13 tests/31 assertions without
production source overlays. The parent-relative standards scan is clean and
inferred strata stay within three. The change is split into two normal commits:
tested signal/knowledge/boundary helpers, then orchestration and fault regressions.

After #2080 merged, all three consumers passed again (1m29s); the final CLI is
39,226,563 bytes. The actual-main standards scan has no violations and the change
is 285 reportable lines before this proof update. Parent conflicts retained the
reviewed ordered failure fixture and documentation unchanged.

Final adversarial review consolidated repeated execution-context fixtures and
added assertions for phase-result and metric preservation. The packaged suite
now passes 13 tests/33 assertions; production code and the built artifact are
unchanged by this final test-only refinement.
