<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: retry verification runs off the consumer pass; failures carry their reason

## Overview

Second of six stacked PRs. The operator component can verify a launched
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
  `:resume-not-started`). An unknown code falls back to
  `:resume-not-dispatched`.
- A wait the launcher reports as `:resume/pending?` (its process is stopping)
  records nothing; the intervention stays `:dispatched`. The launcher's
  optional `:settle!` is told every outcome that is recorded.
  `verify-launched-resume!` finishes a pending one after a restart, from the
  intervention and launch the launcher kept.
- `retry-intervention?` lets a process that may exit mid-verification decline
  retries. A consumer also leaves a decision in place when it declines the
  parked intervention the decision is about.
- An evaluator anomaly fails `:policy-evaluation-refused` with its
  `:failure/reason`. `:invalid-policy-evaluation` is kept for results that are
  neither.
- `fail!` merges `:failure/reason`, `:failure/log`, `:resume/run-id` and
  `:resume/pid` into the intervention's details, and fills the localized
  message from them.
- The consumer writes its cursor after every file that changed it, and ends a
  pass before the next file once it is being stopped.
- `stop-operator-consumer!` drains the poller, then the verification pool.
  Each gets 10 s, is then interrupted, and gets 10 s again to finish.
- The interface gains `live-runner?`, `retry-intervention?` and
  `verify-launched-resume!`.

## Testing Plan

- `application-test`: verification off the pass, launcher codes and details, an
  unknown code, and an evaluator refusal. A stop interrupting a verification
  records nothing and settles nothing; after a restart the same launch is
  verified and settled.
- `consumer-test`: the cursor is on disk before the next file runs; a stop ends
  the pass; stop lets an in-flight pass finish.
- `consumer-test`: a declined decision is left for a consumer that accepts it.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Launchers without `:await-start!` behave as before.

## Related Issues/PRs

Stack: `feat/resume-flags`, this PR, `feat/resume-launcher`,
`feat/resume-launcher-hardening`, `feat/shared-process-handles`,
`feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the operator catalog
- [ ] Review comments addressed; CI green
