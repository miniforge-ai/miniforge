<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# test: Gate-warning pass cases use an acknowledging stream

## Overview

This fixes a red main. Two pass-path cases in
`apply-gate-validation-keeps-gate-warnings` (`phase_transitions_test.clj`)
now get a stream with a durable sink, the same ctx the sibling
approved-verdict test uses. The assertions are unchanged.

## Motivation

Main failed three assertions in that test. The cause was a semantic
conflict between #2303 and #2311. #2311 made a gate decision count as
acknowledged only once a durable sink writes it, and it updated the
sibling test. #2303 added two pass-path cases that streamed to
`{:sinks []}`. Each PR passed CI on its own, but #2303 merged after #2311
without CI re-running against it.

With no durable sink, the decision went unacknowledged and the phase was
projected to `:failed`, so the "a warning leaves the phase passing"
assertions failed. That is correct behavior under #2311; the test ctx was
stale.

## Changes in Detail

- A new test helper, `acknowledging-ctx`, builds a stream with one
  `events/durable-sink`.
- Both pass-path cases use it.

## Testing Plan

CI ran the workflow tests. They need Clojars dependencies that are
unreachable from the authoring environment. The ctx copies the sibling
test's, which is green on main.

## Deployment Plan

Test-only. Nothing to deploy.

## Related Issues/PRs

- #2303: added the cases.
- #2311: changed what an acknowledged decision is.
- Merged as `cc2c646`.

## Checklist

- [x] Assertions unchanged; only the test ctx changed
- [x] CI green on a head containing current main
- [x] Copilot review clean
