<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: support deadline-bounded native process input

## Overview

Extend the existing deadline transport to require complete stdin delivery and
complete output capture within one monotonic request budget.

## Motivation

Merged #2536 bounds the HTTP request but not an unresponsive local CLI or pipe.
Uncertain submission must remain a refusal, never a retry or name-only adoption.

## Changes in detail

Runtime infrastructure in dag-executor, based on main. Extend the existing
deadline command boundary with bounded stdin; retain its no-input contract and
shared process cleanup. A dependent PR will adopt this transport for native Job
creation after the separate admission changes settle. This PR changes no Job
submission or remote effect policy.
The existing OCI helper has asynchronous stdin, but does not confirm completion
of that writer before success and couples transport to a runtime descriptor.
No new provider abstraction or duplicate capture/shutdown implementation is needed.

## Testing plan

Use disposable local processes to prove complete input delivery, deadline and
cancellation behavior, blocked input and bounded output. Retain the current
read-only command and setup-failure regressions. Verify
both JVM products, source Babashka and a fresh package. No live provider calls.

Each mode passes 21 tests and 69 assertions, including four-MiB delivery through
native backpressure, early exit without accepting input, a stalled writer,
reader/writer setup failure, cancellation and owned-process cleanup. Independent
controls produce one failed-input assertion, one final-deadline assertion, two
writer-completion assertions and one worker-cancellation assertion, with zero
runtime errors on JVM and native Babashka. Four exact safe namespace examples
also pass on both runtimes. Strict lint and Polylith checks pass; each changed
implementation namespace has at most three actual dependency strata.

## Deployment plan

No infrastructure actions. Killing an owned local CLI cannot recall a dispatched
remote request; this is not containment, reconciliation or an atomic full stop.
Existing Job lifetime remains an independent bound.

## Related issues/PRs

Merged #2542 owns the deadline/capture/shutdown primitives. Merged #2536 owns
the creation receipt. #2554 independently verifies admission-time Pod ownership.

## Checklist

- [x] Implementation and owner standards/adversarial pass.
- [x] Four-mode verification and independent negative controls.
- [ ] Current-head CI, automatic and full review, all threads resolved.
