<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(llm): opt out of the empty-stream fallback

## Overview

A Claude CLI stream that exits 0 with no content, no tool calls and no usage
is retried once through the buffered path (`blank-streaming-success?`, added
for the 2026-05-02 planner failure). That retry is a second provider call the
caller never sees. This adds a request option,
`:empty-stream-fallback? false`, that returns the empty stream as it is.

## Motivation

The deliberation lab (#2347) journals every provider call before making it
and promises never to retry. With the fallback, one journaled activation can
make two calls, the second unjournaled (recorded as a follow-up in #2347's
PR doc and the atlas README, #2349). The lab needs to switch the retry off;
other callers keep today's behaviour.

## Changes in Detail

- `llm_client/handle-streaming`: the fallback runs unless the request sets
  `:empty-stream-fallback? false`. Absent or any other value keeps the
  retry, so existing callers are unchanged.
- `llm/complete-stream`'s docstring documents the retry and the option.
- With the option off, an empty stream takes the normal streaming-success
  path. Its usage is nil, so `llm-success` reports zero tokens; a caller that
  requires metering, such as the lab adapter, treats it as a failed call.

The lab adapter sets the option in a follow-up PR (base stratum, dewey 722).

## Testing Plan

- `interface_test/complete-stream-impl-empty-stream-fallback-opt-out-test`:
  with the option off, an empty stream makes no buffered call and returns
  blank content. The existing fallback test still covers the default.
- clj-kondo (CI version) on `components/llm`: 0 errors.

Ran only in CI: the `llm` suites. Clojars is unreachable from the authoring
sandbox, and the scratch stand-ins could not load the `response` component's
slingshot `try+`.

## Deployment Plan

None. Opt-in request option; the default is unchanged.

## Related Issues/PRs

- Follow-up from #2347 and #2349.
- Next: the lab adapter sets the option.

## Checklist

- [x] Default behaviour unchanged
- [x] New behaviour has a test
- [x] Copyright header on the PR doc
