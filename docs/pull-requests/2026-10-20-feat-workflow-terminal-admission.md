<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: enforce terminal publication admission

## Plan and scope

Application layer, aligned to main `2493d519b`. Depends on merged receipt
preservation #2067 and authoritative publication-state policy #2068
(built on #2066). Admit the terminal receipt before returning and
checkpointing the final runner outcome. Refusal must become a failed machine
state, not just a contradictory projected status. Preserve nil/legacy and
explicit skip-lifecycle behavior, original phase facts, and cleanup.

## Verification plan

Trace actual runner ordering and cleanup. Test acknowledged completion,
publication refusal, physical checkpoint/reopen, and compatibility paths.
Keep auxiliary notification failures distinct from terminal refusal.
Use normal commit/PR budgets, adversarial standards review, serial consumer
tests, rebuilt isolated package tests, signed full hooks, fresh exact-head review
and all CI including Build. No size exception applies.

## Verified implementation

The runner delegates finalization and failure recovery to focused internal namespaces.
Terminal refusal appends a typed error without losing earlier failures, applies
the authoritative refusal transition, and regenerates output status before the
final checkpoint. Cleanup receives execution facts even if publication is fatal.
The startup regression now selects its original refusal by type because a closed
journal can legitimately refuse both startup and terminal failure publication.

The old packaged runner reproduced eight failures / 35 assertions after the
test fixture was corrected to reach the terminal boundary. Shared legacy phase
completion publication is isolated in that test; terminal event construction,
journal acceptance/refusal, checkpoint save/reopen, and cleanup remain real.
All three consumers passed serially (1m31s), rebuilt CLI 39,199,111 bytes, and
isolated packaged regressions passed 43 tests / 290 assertions with test fixtures
only, no production source overlays. Nil/legacy and explicit skip behavior remain
covered. Adversarial review traced receipt ownership, output projection,
checkpoint ordering, retained phase facts and the fatal-error cleanup path.

The N3 §9.1 cross-check added one bounded failure-publication attempt when a
non-failed terminal outcome is refused. A second refusal is retained as a typed
diagnostic, never retried recursively; an already-failed outcome is not
republished. Real-journal regressions cover the available failure path and the
double-refusal case. The follow-up passed all three consumers (1m34s), rebuilt
CLI 39,199,812 bytes, and isolated package 44 tests / 300 assertions.
An additional serialized JVM run passed 37 tests / 681 assertions across N7 OPSV
shared lifecycle, finalization, current terminal evidence, post-actuation
recovery, typed checkpoints and native failure publication.
Main alignment changed only the dependency documentation; the production and
test tree remains identical to the tree used for those verification results.

Review caught a fatal fallback-publication path that left cleanup reporting
success. Failure projection and its checkpoint now precede the bounded fallback
publication. A class-only fatal-error boundary also marks cleanup failed when the
initial publication throws, then rethrows the same Error. Real cleanup tests
verify the failed observation signal in both paths and the failed checkpoint
before fallback. The old package reproduced four failures / seven assertions.
The fix passed all three consumers serially (1m31s), rebuilt CLI 39,199,999 bytes,
isolated package 46 tests / 313 assertions, and the N7 JVM integration suite
37 tests / 681 assertions. Namespace responsibilities remain separated, shared
test fixtures avoid duplicated setup, and all inferred namespaces stay within
three strata. The standards scan then required the fatal catch/rethrow to live
at an explicit boundary. `workflow.boundary.terminal` now owns that boundary;
terminal orchestration passes the publication operation without handling
exceptions itself. Final verification after this decomposition passed all three
consumers (1m33s), CLI 39,200,486 bytes, package 46 tests / 313 assertions, and
N7 JVM 37 tests / 681 assertions.

This does not yet make checkpoint I/O errors explicit or deduplicate terminal
republication across a process restart; those remain separate durability work.

## Deployment

Normal package rollout; the state-policy dependency is merged.
No new emitted event type. No claim of N7 completion from this slice alone.
