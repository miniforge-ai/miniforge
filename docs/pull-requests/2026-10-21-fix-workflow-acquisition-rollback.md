<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: roll back partial workflow environment acquisition

## Scope and dependency

Depends on environment release #2086. Do not open against main until that parent
is merged and this branch is aligned. Keep the parent's branch intact.

Transfer acquired resource ownership only after constructing its record. A failed
record construction releases the environment. Governed acquisition obtains its
worktree before its capsule; rejection or failure releases the worktree instead
of orphaning it. Missing worktrees prevent capsule acquisition. Successful pairs
retain both resources for the existing runner cleanup path.

Use the shared cleanup policy for fatal priority, original failures, and temporary
interrupt clearing during rollback. Preserve existing ordinary release warnings;
this does not change executor result contracts or add journal event types.

## Verification

Public acquisition regressions cover missing/rejected worktrees, refused and
throwing capsules, record-construction interruption, rollback failure priority,
and successful ownership transfer. Run JVM consumers serially, verify the built
CLI without production overlays, and retain all standards, budget, signing,
fresh-review and CI gates. No verification is claimed until those runs complete.

The parent CLI fails ten assertions in the six new ownership tests; the corrected
implementation passes all three workflow consumers (1m28s). The rebuilt CLI is
39,230,020 bytes and passes fourteen isolated acquisition/release/notification
tests with 54 assertions. New tests take 0.2–7.9 ms on the JVM and remain in the
brick suite. The standards scan is clean; the scoped change is 158 reportable
lines, within the normal commit budget. Parent alignment and PR gates remain.
