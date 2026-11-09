<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(opsv): share governed forward-runtime composition

## Overview

Keep the existing governed load-start API while extracting its shared host flow.
Separate this prerequisite from inert creation to preserve bounded review scope.

## Motivation

N7 creation and start need the same current admission, issuance, pinned commit,
audit and failure cleanup. Share those mechanisms instead of copying their maps
or orchestration. Keep the existing start operation as a live consumer.

## Changes in Detail

Move implementation namespaces to their shared names. Keep operation descriptions
code-owned, with named handlers and closed input validation at the existing edge.
Separate pure outcome values from transaction I/O. Preserve current N4/N8 checks,
exact independent authority, audit acknowledgement, fencing and interrupted cleanup.
This is restructuring, not new effect authority or a new public creation API.
Generalize existing system diagnostic wording without changing scope or outcome semantics.

## Testing Plan

Validate both consuming JVM products, strict lint/true strata, structure, task
classpaths and a fresh isolated package. Exercise existing real-producer start,
durability and audit regressions with meaningful authority/readiness/cleanup faults.
Only HTTP is simulated. Run local JVM work serially under the shared protocol.

## Deployment Plan

No activation, live trials, image pulls, traffic, cluster changes or new authority.

## Related Issues/PRs

Base: main after #2490 and #2492. Native inert creation follows separately.

## Checklist

- [ ] Extract shared runtime with the existing live start consumer.
- [ ] Source, JVM, package and fault validation.
- [ ] Owner adversarial and full applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
