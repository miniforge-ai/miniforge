<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Wire persist-worklist! into observe_phase enter-observe.

**PR:** [#1202](https://github.com/miniforge-ai/miniforge/pull/1202)
**Branch:** `mf/wire-persist-worklist-into-observephase--b4d7708a`

## Summary

Wire persist-worklist! into observe_phase enter-observe.

## Files Changed

- `components/workflow/src/ai/miniforge/workflow/observe_phase.clj` (modify)
- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

Implementation correct. Persist call wired in the right place — before the monitor future, inside the non-empty-pr-infos branch. Layer 1/2 split is clean: pr-url->repo and pr-info->worklist-entry are pure helpers; remote-origin-url and try-persist-worklist! are effectful, correctly placed in Layer 2. try+ used for all new catch sites (rule 211 compliant). ms-per-second named constant with docstring (rule 006 compliant). No new user-facing string literals — log calls use keyword events only. Test coverage is comprehensive for new functions. Two warnings about the self-author parameter and the missing max-fix-attempts config fields — both point at the same question: what does the resume path actually need? Resolve that, and the PR is clean. One test failure (oci_cli_test) is pre-existing and unrelated.

### Known issues (non-blocking)

Merged with 2 unresolved non-blocking issue(s) recorded for follow-up:

- `components/workflow/src/ai/miniforge/workflow/observe_phase.clj` — self-author parameter accepted by try-persist-worklist! but never referenced in the function body and not included in the persisted worklist entry. Task spec explicitly lists self-author as data to pass for persistence purposes. Unused parameter (rule 008 spirit). More importantly: if the resume path needs self-author to filter PRs by author, the omission is a functional gap — process restarts would poll without an author filter and find nothing.
- `components/workflow/src/ai/miniforge/workflow/observe_phase.clj` — Task spec requires persisting the serializable subset of monitor-config: poll-interval-ms, max-fix-attempts-per-comment, max-total-fix-attempts-per-pr, abandon-after-hours. Current implementation only propagates poll-interval-ms and abandon-after-hours (via pr-info->worklist-entry). max-fix-attempts-per-comment and max-total-fix-attempts-per-pr are present in monitor-config but never extracted into the persisted entry. If resume reads these to restore retry-count state, the omission is a correctness gap.
