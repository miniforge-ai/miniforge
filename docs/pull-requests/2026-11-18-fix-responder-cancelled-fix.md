<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(pr-lifecycle): a cancelled fix run is not a fix

## Overview

The PR responder no longer treats a fix workflow the operator cancelled as a successful fix, so
it no longer replies "Fixed" to the review comments and resolves their threads for work that was
stopped. Getting there needed a movement-only split of `responder.clj` first.

## Motivation

1. `fix-succeeded?` counted any result whose status was not `:failed` as a fix. A run ended
   `:cancelled` (N2 §2.2), which the cancel work in progress makes the outcome of every operator
   cancel, would have been replied to and resolved as fixed, and pushed.
2. `responder.clj` had 4 layers, over the 3-layer limit (rule 210). The pre-commit stratum check
   blocks any edit to it until it is split.

## Changes in Detail

1. The first commit moves forms verbatim into three namespaces:
   1. `responder-comments`: fetch, filter and group review comments;
   2. `responder-fix`: one comment group's fix spec, run and replies;
   3. `responder`: PR URL parsing and `respond-to-comments!`, whose calls into the other two
      are qualified.

   `process-comment-group` becomes public, since the orchestrator now calls it across a
   namespace, and `respond-to-comments!` is retagged from stratum 3 to 2. There are no
   re-exports: the interface's `build-fix-spec` and the tests call the new namespaces, so a
   test that redefines one of the moved functions reaches its caller.
2. The second commit makes `fix-succeeded?` treat `:cancelled` like `:failed`. A cancelled DAG
   that opened PRs for its completed tasks still counts as a partial fix, as a failed one does.

## Testing Plan

1. On the split alone, every test touching the responder passes: `responder-test`,
   `policy-eval-responder-test`, the two responder anomaly tests and the CLI's `pr-monitor-test`,
   49 tests.
2. New `respond-to-comments-cancelled-fix-test`: a cancelled fix run is not a success, gets no
   reply and is not pushed. It fails on the split alone and passes with the fix. New
   `respond-to-comments-cancelled-dag-with-prs-test`: a cancelled DAG that opened PRs is still a
   partial fix.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2546: the runner change that ends a cancelled run `:cancelled` instead of
   `:failed`. This PR merges before it.
2. miniforge-ai/miniforge#2377: the cancel acceptance issue.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
