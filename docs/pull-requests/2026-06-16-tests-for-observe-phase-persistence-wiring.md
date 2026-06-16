<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Tests for observe_phase persistence wiring.

**PR:** [#1204](https://github.com/miniforge-ai/miniforge/pull/1204)
**Branch:** `mf/tests-for-observephase-persistence-wirin-d79a98a1`

## Summary

Tests for observe_phase persistence wiring.

## Files Changed

- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

All three spec-required tests present and passing: persist-worklist! called with valid pr-infos + self-author (both key shapes), skipped on empty pr-infos, and phase result shape unchanged on persist failure. 106 tests green; one pre-existing out-of-scope failure in orchestrator_supplemental_test. Four nit-level magic number violations (rule 006): 30000, 48, 123456789, 3. One nit on multi-form anon fn in monitor-loop mock (rule 002). One nit on narration comment (rule 009). No blocking issues.

### Known issues (non-blocking)

Merged with 5 unresolved non-blocking issue(s) recorded for follow-up:

- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj:106` — Magic literals 30000 and 48 appear inline in the ':pr-url + :pr-number key shape' sub-test of pr-info->worklist-entry-test. Both exceed the rule-006 threshold (> 2). They are clearly probe values chosen to differ from the primary constants, but that intent is invisible without extraction.
- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj:133` — Magic literal 123456789 (a stub epoch-ms value used in the 'timestamp comes from the now argument' assertion) has no name. Rule 006: every numeric literal > 2 should be checked.
- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj:170` — Magic literal 3 appears inline as `:max-fix-attempts-per-comment 3` inside resolve-monitor-config-test. Rule 006 flags literals > 2 in production and test code alike.
- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj:196` — The run-pr-monitor-loop mock in enter-observe-detaches-monitor-test uses a multi-form anonymous function body (deliver, deref, return map). Per rule 002 / feedback_anon_fn_guidance: extract to a named defn- when the body exceeds one non-trivial expression. Test code is not exempt.
- `components/workflow/test/ai/miniforge/workflow/observe_phase_test.clj:205` — The block comment above enter-observe-calls-persist-worklist!-test narrates what the test does ('Verify persistence wiring: when pr-infos are present...'). Rule 009 asks for WHY comments, not WHAT-the-code-does narration. The test name and structure say what it does.
