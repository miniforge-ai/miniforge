<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel ends PR-train monitoring and merges no further PR

## Overview

A cancel now ends the PR monitor phase. Its poll wait ends on the cancel, and no further PR is
merged once the run is stopped. The phase then fails and names the PRs merged before the cancel.
The runner records the stop (#2277).

## Motivation

1. The PR monitor phase runs `dag-monitor/monitor-pr-train`. It loops up to 100 cycles and
   sleeps 30 s between them, with no check of the run's control state.
2. A cancel during monitoring waited up to 50 minutes. Each cycle could still merge a ready PR,
   which is an outward action the operator asked to stop.
3. The phase did not pass the run's control state to the monitor.

## Changes in Detail

1. `dag_monitor.clj` had layer headings without stratum metadata, so its first edit rewrites the
   whole file. The autofix alone gives it four layers, and the hook allows three. Two of those
   layers came from one-line helpers with a single caller each, not from strata of their own. The
   first commit adds the metadata and folds them into their caller:
   1. `all-prs-terminal?` takes `pr-terminal?`'s set check;
   2. `build-monitor-result` builds the map that `monitor-result` built;
   3. `all-prs-merged?` had no caller and is removed.
2. `monitor-pr-train` reads `:control-state` from its context. A stopped run ends the loop with
   the merged and failed PRs so far, `:success? false` and `:stopped? true`. The poll wait uses
   `stop-signal/sleep!`.
3. `execute-monitor-cycle!` takes the control state and attempts no further merge once the run is
   stopped. It has no other caller.
4. `pr-monitor/enter-pr-monitor` passes the run's control state to the monitor. A stopped monitor
   fails the phase with `:stopped?` and `:merged-prs` in the failure data.

## Testing Plan

1. New `dag_monitor_cancel_test.clj`:
   1. a cancel during a cycle skips the 10 s poll wait after it;
   2. a cancel during the first of two merges stops the second, and the first is reported;
   3. with no control state, or a stop after every PR finished, the result is the ordinary one.
2. `pr_monitor_test.clj`: the phase passes the run's control state to the monitor. A stopped
   monitor fails the phase and names the PRs merged first.
3. The new tests fail on the restructure commit alone and pass with the fix. The existing
   `dag-monitor-test` and `pr-monitor-test` pass.

## Notes

1. The max-cycles path still returns no merged or failed PRs. That gap predates this PR.
2. `execute-monitor-cycle!` keeps its plain `try`. Rule 211 leaves existing catch sites as they
   are, and in `try+` a `catch Exception` would no longer catch an ex-info.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel issue.
2. miniforge-ai/miniforge#2277: the cooperative cancel and `stop-signal` this builds on.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
