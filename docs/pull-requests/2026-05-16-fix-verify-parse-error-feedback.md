<!--
  Title: Fix Verify Parse Error Feedback
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix Verify Parse Error Feedback

## Summary

Dogfood resume showed repeated `verify -> implement` redirects with the message:

```text
Tests failed: 0 failure(s), 0 error(s)
```

That message is internally contradictory and gives the implementer nothing to
repair. The actual condition was an unparseable test runner output: Verify was
setting `:parse-error? true`, but then formatting the failure with zero failures
and zero errors.

Verify now treats parse errors as test-runner errors and includes a bounded
preview of the raw output in the phase error. Repair prompts should now carry
the compiler/test output that caused Verify to redirect.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.verify-failure-modes-test 'clojure.test)
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-failure-modes-test)"`
