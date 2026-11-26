<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve native-reader critical failures

## Overview

Keep filesystem failure conversion from swallowing fatal errors or interruption
wrapped by a background native reader's future.

## Layer and dependencies

Infrastructure boundary fix, branched from main after #2566. Independent of
qualified-root admission; #2555 and the authority-file query consume this fix.
Separating it preserves the ordinary size budget for each review.

## Motivation

The existing catch handles direct interruption but turns future-wrapped fatal
errors and interruptions into ordinary I/O anomalies. The producer-shaped
regression reports two failures with no test errors before the correction.

## Changes in detail

Reuse resource-cleanup's cause-chain classification at the existing filesystem
exception boundary. Propagate fatal errors; restore the calling thread's flag
for interruption and return the existing anomaly. Ordinary failures and structured
anomalies retain their contracts. Extend the existing boundary test to exercise
both direct throws and actual future-produced wrappers, without filesystem I/O.

## Testing plan

Run boundary tests and exact namespace examples on Java 21 and stock Babashka.
Disable classification as a control, requiring failures with no test errors.
Run normal commit checks, current-head CI, automatic and full review.

Local Java 21 and stock Babashka each pass three boundary tests / 58 assertions.
Disabling shared cause classification produces three expected failures and no
test errors in each runtime. Two exact namespace examples pass in each runtime.

## Deployment plan

No new runtime dependency or configuration. No infrastructure actions, live
experiments, permission repair, or authority grants.

## Checklist

- [x] Cross-runtime regressions and fault controls.
- [ ] Owner actual post-hook standards and adversarial review.
- [ ] Current-head CI, automatic review, one settled full review and normal merge.
