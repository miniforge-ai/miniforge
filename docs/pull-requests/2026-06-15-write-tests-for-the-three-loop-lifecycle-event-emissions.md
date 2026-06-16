<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Write tests for the three loop lifecycle event emissions.

**PR:** [#1191](https://github.com/miniforge-ai/miniforge/pull/1191)
**Branch:** `mf/write-tests-for-the-three-loop-lifecycle-623655ad`

## Summary

Write tests for the three loop lifecycle event emissions.

## Files Changed

- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

Five test cases all present and structurally sound. Tests pass. Stubs are clean: no-prs, one-pr-then-empty, no-new-comments, and run-with-stubs compose well. Event ordering test is thorough — checks started < first-completed and stopped == last. One :warning on magic-number fixture constants (rule 006); five :nits on docstring accuracy and one assertion derivation. No blocking issues.

### Known issues (non-blocking)

Merged with 7 unresolved non-blocking issue(s) recorded for follow-up:

- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:79` — Magic numbers in make-test-monitor config stub violate rule 006 (named-constants). Values :max-fix-attempts-per-comment 3, :max-total-fix-attempts-per-pr 10, and :abandon-after-hours 72 are all above the threshold that requires checking. Test code is not exempt per rule 006: 'Named-constant rule is stricter than other style rules in tests because test code is also documentation.' Each deserves a private def with a one-line docstring stating its intent as a stub ceiling.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:65` — Magic number 99 for :pr/number in fake-pr. Single-occurrence but still above the rule-006 threshold to 'check if it deserves a name.' For test documentation, a named constant signals intent more clearly than a bare literal.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:144` — Inner testing block label says 'heartbeat fires even when poll-pr-for-new-comments returns zero comments' but the assertion only checks that every iter-events entry has a nat-int? :iteration — which was already confirmed by the count and sequential assertions above. The label describes a causal claim; the assertion doesn't demonstrate it. The no-new-comments stub is wired globally for all runs in this test via run-with-stubs, so the causal relationship is real, but the assertion doesn't express it.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:126` — testing label 'finalize-loop-iteration! emits ...' names a private implementation function. Tests should express the observable contract, not the implementation path. The label ties the test to the internal function name, which will silently mis-document if the function is renamed.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:72` — ok docstring says 'dag/err? treats as success' but the map shape uses :ok? true, not an :error or :err? key. dag/err? typically tests for error presence; the described predicate doesn't match the key in use. Minor but misleading for anyone reading the stub to understand what the SUT expects.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:77` — make-test-monitor docstring states ':event-bus is required for emission assertions' but structurally it defaults to nil when called with {} or no args. The zero-arity call path (make-test-monitor) would silently produce a monitor with :event-bus nil, not an error. The docstring overstates the constraint.
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_loop_test.clj:141` — Assertion (= [1 2 3] (mapv :iteration iter-events)) hardcodes the literal sequence rather than deriving it from n-iters. If n-iters changes, the expected-vector must be updated separately.
