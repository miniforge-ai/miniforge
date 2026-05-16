<!--
  Title: Fix Implement Leave Nil Start Time
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix Implement Leave Nil Start Time

## Summary

Dogfood resume run `3927baf8-c9db-44d3-b5fb-5a1552dbe554` repeatedly recorded
`:leave-error` anomalies in the implement phase with a null-pointer exception
and no useful message. The run continued through checkpoint persistence, but the
error history made the eventual workflow failure harder to understand.

`leave-implement` assumed `[:phase :started-at]` was always present and computed
duration with `(- end-time start-time)`. Other leave handlers already tolerate a
missing start time; implement now follows that same pattern and records duration
`0` when the enter context did not establish a start timestamp.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.implement-test 'clojure.test)
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.implement-test)"`
