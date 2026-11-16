<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: define run full stop and safe checkpoint recovery

## Overview

Define a full stop distinct from cancellation and safe mode: fence all run work,
including recovery, quarantine its authority, and destroy only its owned capsule.

## Motivation

Cancellation permits independently authorized compensation. Operators also need
an instruction that stops that compensation and contains an unresponsive run.
Terminating a capsule cannot recall an externally accepted request.

## Approval record

On 2026-11-15 the operator requested run-owned isolation shutdown and safe
checkpoint restart, contingent on verifying the recommended all-writes stop.
They also approved containment-only audit-outage handling with authorization,
failure reporting, and audit obligations intact. No live actions are authorized.
The recovery approval contingency remains open until implementation verification.

## Changes in detail

Contract layer; base branch `main`, no prerequisite PRs.
N8 owns the full-stop command and its dominant fence; N11 owns containment and
termination; the N2 checkpoint delta owns safe restart. N4 registers the
distinct permission. N7/N10 reference the
dominant fence. N3/N5 coordinate the narrow audit-outage exception; N6 remains
authoritative. No event or artifact family is added. Review fixes distinguish
ordinary compensation from full stop and require confirmed old-runtime
containment, termination, and durable fencing before fresh-run effects.
Hook-required prose cleanup preserves existing requirements and historical records.

## Testing plan

- Trace cancellation, full-stop failure, restart, uncertain external effects,
  and terminal-run recovery across the amended contracts.
- Run formatting, document checks, normal hooks, and all PR CI/review gates.
- No runtime or deployment conformance claim from this documentation-only PR.

## Deployment plan

Contract only. Follow with durable fencing, owned-runtime termination, and
checkpoint/control-surface integration in separate bounded PRs. No live actions.

## Related issues/PRs

- #2499: separately authorized OPSV recovery; conditional human approval.
- #2500 (merged): child stop retains registration, not an all-writes stop.

## Checklist

- [x] Cross-contract trace and standards review complete.
- [x] Local documentation checks complete (prose lint, formatting, whitespace).
- [ ] Current-head CI and Copilot review complete; findings settled.
