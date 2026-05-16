# Fix Resume Status Stale Failure

## Summary

`miniforge status <workflow-id>` reported `failed` for an actively resumed
workflow if the event stream still contained a historical `:workflow/failed`
event from an earlier attempt. Checkpoint state already records the current
execution status, so resume reconstruction now treats a checkpoint status as
authoritative when present.

## Changes

- Make workflow resume reconstruction prefer checkpoint `:execution/status`
  over historical terminal events.
- Add regression coverage for a running checkpoint with stale failure events.

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow-resume.core-test 'clojure.test) (clojure.test/run-tests
  'ai.miniforge.workflow-resume.core-test)"`
- `bb miniforge status 3927baf8-c9db-44d3-b5fb-5a1552dbe554`
- `bb pre-commit`
