<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: publish activation-local current heartbeats

## Overview and motivation

Wire the approved N3 heartbeat contract to current streams without treating
scheduler ticks as substantive work. Reuse the progress model and shared
scheduler owner. Preserve the legacy producer profile.

## Scope and dependencies

Depends on typed admission (#2073), pure progress (#2074) and scheduler ownership
(#2075). Validate current start options before allocating resources, own each
activation explicitly, and count failed ephemeral attempts without changing
progress. Stop must fence further ticks and progress observations. Unknown
instrumentation remains explicit. Runner source instrumentation and observer
presentation follow separately; this PR does not claim N7 completion.

## Verification plan

Deterministic clock/task tests cover actual current publication and replay,
duplicate progress, failed emission, close/start refusal, re-entry isolation,
stop fencing and resource cleanup. Preserve legacy tests. Run consumer projects
serially, rebuild the isolated package, and retain all normal signed-commit,
standards, review and CI gates. Open only after dependency PRs merge.

All four event-stream consumer projects passed serially (1m48s). Fresh CLI
build: 39,213,354 bytes. Isolated package tests passed: 10 tests, 70 assertions,
including the closed-activation fence. No production source overlays were used
for the package check. The implementation commit is 193 reportable lines;
the integration fixtures and scenarios are a separate normal-budget commit.

After merging the final reviewed scheduler cleanup, the four consuming projects
passed again (1m48s). Final CLI: 39,214,031 bytes. Isolated runtime, fence and
cleanup-graph tests passed: 15 tests, 80 assertions. The actual PR diff against
merged main contains 389 reportable lines; parent implementation changes are
excluded. Merge conflict resolution retained the reviewed cleanup implementation
and the corrected documentation author header.

Runtime measurements identified journal scenarios at 102–289 ms. Those scenarios
now live in the miniforge project integration suite and are registered with the
integration runner; the fast boundary/fence tests remain in the brick. Project
test composition includes the shared event-stream fixtures. This enforces the
Polylith 100 ms placement rule without duplicating fixtures or losing CI coverage.
After relocation, the real project JVM passed four tests/33 assertions, and the
isolated package passed all 15 tests/80 assertions again.
