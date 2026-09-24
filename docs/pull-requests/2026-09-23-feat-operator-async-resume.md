<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: retry verification runs off the consumer pass; failures carry their reason

## Overview

Second of four stacked PRs. The operator component can verify a launched
retry without holding the consumer's pass. It records each file before the
next one runs, stops a pass between files, and puts the reason for a failed
intervention on the intervention.

## Motivation

A resume launcher that waits for the child it started would hold the consumer's
pass. The pass holds the cross-process `.consumer.lock`, so every other
process's pause or cancel would wait too. The cursor was written only at the
end of a pass. A process that exited mid-pass left the files it had acted on
unrecorded, and they were delivered again. Failed interventions kept a bare
code, so the operator could not see why a retry did not start or why a
re-evaluation gave no verdict.

## Changes in Detail

- Resume launchers may supply `:await-start!`. With it, `apply-resume-verb!`
  returns once the run is spawned; the intervention stays `:dispatched`. The
  wait and the readback run on a verification pool. A launcher without it is
  verified inline, as before.
- A launcher's anomaly may name a `:failure/code` the lifecycle knows
  (`:resume-in-flight`, `:resume-target-live`, `:resume-origin-unknown`,
  `:resume-not-started`, `:resume-unverified`). An unknown code falls back to
  `:resume-not-dispatched`.
- An evaluator anomaly fails `:policy-evaluation-refused` with its
  `:failure/reason`. `:invalid-policy-evaluation` is kept for results that are
  neither.
- `fail!` merges `:failure/reason`, `:failure/log`, `:resume/run-id` and
  `:resume/pid` into the intervention's details, and fills the localized
  message from them.
- The consumer writes its cursor after every file that changed it, and ends a
  pass before the next file once it is being stopped.
- `stop-operator-consumer!` drains the poller, then the verification pool.
  Each gets 10 s, is then interrupted, and gets 10 s again to record the
  outcome.
- The interface gains `live-runner?`.

## Testing Plan

- `application-test`: verification off the pass, launcher codes and details, an
  unknown code, a stop interrupting a pending verification and recording it,
  and an evaluator refusal.
- `consumer-test`: the cursor is on disk before the next file runs; a stop ends
  the pass; stop lets an in-flight pass finish.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Launchers without `:await-start!` behave as before.

## Related Issues/PRs

Stack: `feat/resume-flags`, this PR, `feat/resume-launcher`,
`feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the operator catalog
- [ ] Review comments addressed; CI green
