<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-workspace): projection read scope and withheld frame

## Overview

Adds the runtime read scope to projections and reports the task frame a scope
withholds. The run loop in #2332 consumes both: it renders each participant's
projection under that participant's scope and refuses a turn whose frame is
incomplete. This was split out of #2332 to keep that PR within the 600-line
budget; it is a separate domain-layer piece with no orchestration in it.

## Motivation

N14 0.3.0 §4.1 makes the projection a deterministic function of inputs that
include the participant's authorized read scope. §4.2 requires every
projection to carry the goals and `:hard` constraints. When a scope cannot
disclose them, the runtime must refuse the turn or obtain an authorized
reformulation, never run it on a partial frame. On main, `project` has no read
scope and cannot report a withheld frame.

## Changes in Detail

- `projection`
  - `project` takes an optional `:readable-ids` set. When it is given,
    nothing outside it is visible, under either visibility mode. When it is
    absent, behaviour is unchanged.
  - `visible-objects` takes the scope as a fourth argument.
  - New `withheld-frame`: the sorted ids of goals and `:hard` constraints
    outside the scope, or `[]` when no scope is set. `project` returns it as
    `:projection/withheld-frame`.
  - The read scope never broadens: a withheld frame is reported, not
    disclosed.

## Testing Plan

`projection_test` adds two cases:

- the read scope bounds even full visibility;
- a scope that hides a goal reports it in `:projection/withheld-frame`, and
  no scope reports nothing.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): all 12 deliberation-workspace
suites, 171 tests, 552 assertions, 0 failures. clj-kondo 2026.10.26 (the CI
version) on the component: 0 errors. `poly test` and the Polylith check ran
only in CI.

## Deployment Plan

None. Experimental component (N14 §0.4) with no production caller.

## Related Issues/PRs

- Base: main, after #2331 merged. Contract: #2328.
- Next: #2332 (run loop), which is restacked on this branch.

## Checklist

- [x] Read scope only narrows visibility
- [x] Withheld frame reported, never disclosed
- [x] Every new behaviour tested
