<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel ends the retry backoff and the DAG waits outside a phase scope

## Overview

Stacked on #2277. A cancel now ends the retry backoff, the DAG pause wait and the rate-limit wait
at once, with or without a phase scope around them. A cancel that lands during a rate-limit
failover or pause decision now wins over that decision.

## Motivation

These were Copilot findings on #2277, moved to their own PR to keep #2277 under the PR size
limit.

1. The retry backoff runs after the phase scope ends, so a cancel during it waited the backoff out.
2. The DAG's rate-limit and pause waits woke on a cancel only through a phase scope's interrupt.
   Under `mf run plan.edn` there is no phase scope around the DAG loop, so the waits ran to the
   end.
3. A cancel that landed during a rate-limit failover or pause decision lost to that decision. The
   run ended paused, without its spend, and could publish an auto-resume hint.

## Changes in Detail

1. `stop-signal/sleep!` (new) sleeps unless the run is stopped, and wakes on a stop. On a thread
   whose own scope is armed, that scope's interrupt wakes it; elsewhere it runs in a scope of its
   own. It returns whether the run is stopped.
2. `runner-phase-step/execute-phase` uses it for the retry backoff.
3. `dag-loop`: the pause poll and the rate-limit wait (`resilience/*sleep!*`) use it, replacing
   the private `stop-aware-sleep`. The loop lets a stop win over any rate-limit decision and
   keeps the batch's spend. The next iteration returns the cancelled result.
4. `dag-rate-limit/handle-rate-limit-in-batch` publishes no `:dag/paused` or `:dag/resume-hint`
   once the run is stopped.

## Testing Plan

1. `stop_signal_test.clj`: `sleep!` inside and outside a scope. A 20 s retry backoff with a cancel
   at 300 ms returns within 5 s.
2. `dag_operator_control_test.clj`: a cancel during a 2-minute rate-limit wait with no phase scope
   ends the wait and keeps the batch's spend. A cancel during a 3-hour pause decision ends the run
   cancelled with its spend, and no pause or resume-hint event is published.
3. All three fail on #2277 alone and pass here. On #2277 the backoff runs its full 20 s and the
   wait is still running at 5 s. The run ends paused, with no spend and both events published.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2277: the base of this PR.
2. miniforge-ai/miniforge#2377: the cancel issue.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
