<!--
  Title: Fix Implement Leave Nil Iterations
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix Implement Leave Nil Iterations

## Summary

After PR #889, dogfood resume no longer failed on a missing implement
`:started-at`, but the same workflow still recorded `:implement` leave NPEs.
The remaining nil path was `[:phase :iterations]`: resumed or redirected phase
state can carry the key with a nil value, and `get-in` defaults do not apply when
the value is present-but-nil.

`leave-implement` now treats nil iterations as the first attempt. The regression
test covers both nil timing and nil iteration state so future checkpoint resumes
do not reintroduce this failure mode.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.implement-test 'clojure.test)
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.implement-test)"`
