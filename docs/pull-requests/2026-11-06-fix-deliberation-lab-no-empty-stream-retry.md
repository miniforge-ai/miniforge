<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(deliberation-lab): turn off the llm client's empty-stream retry

## Overview

The lab adapter's requests now set `:empty-stream-fallback? false`, the
option #2399 added to the `llm` client. An empty stream is no longer retried
through the buffered path, so every provider call the lab makes is the one
its journal recorded.

## Motivation

The lab journals each call before making it and never retries a failed one.
Before this change, a stream that exited cleanly with no content, tool call
or usage made the `llm` client retry once. That second provider call had
no journal record. The atlas README (#2349) documented the gap; #2399 added
the opt-out in the component, and this base change uses it (dewey 722).

## Changes in Detail

- `adapter/claude-call`: the request carries `:empty-stream-fallback? false`;
  the docstring says why.
- With the retry off, an empty stream returns a blank success whose usage
  reports zero tokens. `call-result` already treats zero input as missing
  metering: a failed call with unknown usage. The activation is charged the same way as before, minus the
  hidden second call.
- `docs/architecture/deliberation/README.md`: the "Running development
  experiments" paragraph now says the adapter turns the retry off, replacing
  the note that the retry escaped the journal.

## Testing Plan

- `lab_test/an-empty-stream-is-not-retried-unjournaled`: with
  `llm/complete-stream` redefined to record its request and return an empty
  success, the call is a failed call and the request carries the flag. The
  test fails with the flag removed.
- `lab-test` and `manifest-test`: 12 tests, 46 assertions, 0 failures
  (local run, scratch `llm` stub on the classpath).

## Deployment Plan

None. Lab-only behaviour for the synthetic experiment runner.

## Related Issues/PRs

- Uses the option from #2399.
- Closes the follow-up recorded in #2347 and #2349.

## Checklist

- [x] Test fails without the change
- [x] Docs updated
- [x] Copyright header on the PR doc
