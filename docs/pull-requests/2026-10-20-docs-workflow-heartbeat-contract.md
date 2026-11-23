<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: formalize workflow heartbeat liveness and progress

## Overview and motivation

The product owner approved registering workflow/phase-heartbeat in N3 with
Workflow scope, ephemeral retention of at least 24 hours, and distinct liveness
and actual progress. Existing legacy heartbeat fields count scheduler ticks;
they cannot safely be reinterpreted as work progress.

## Changes and dependency plan

This contract-only foundation defines payload 2.0.0, explicit progress status,
phase activation identity, monotonic elapsed durations, emission/lifecycle rules,
registry and conformance requirements. Retained legacy records keep their old
meaning and cannot establish current progress. No runtime migration is claimed.
Follow with a small typed-admission/progress-model PR, then scheduler/observer
wiring and actual stall/liveness tests. Normal size limits apply throughout.

## Verification plan

Cross-check N3 sections 3, 4.1, 6 and 7; verify retention minimum, replay/version
semantics and the specification index decision record. Run normal signed hooks,
documentation checks, adversarial standards review, fresh exact-head review and
all applicable CI gates before merge.

## Deployment

Spec amendment only. Implement readers and the new profile before switching
current-journal producers; do not rewrite history or globally migrate unrelated
N3 producers. Legacy mode remains an explicitly separate compatibility path.
