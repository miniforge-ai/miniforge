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

On 2026-11-15 the operator approved the recovery-after-cancellation boundary
contingent on verifying the recommended all-writes stop, and requested:

> As for dispatched requests that we cannot recall, we should have the ability
> to cordon the isolated run and spin the vm (or pod) down (i.e, a "full stop!"
> instruction). Recovery from checkpoint can then restart from the last known
> safe checkpoint.

This authorizes implementation, not live experiments, credential operations,
cluster access, or shutdown of real resources. The approval contingency remains
open until implementation verification; this specification does not satisfy it.

## Changes in detail

Contract layer; base branch `main`, no prerequisite PRs.
N8 owns the full-stop command and its dominant fence; N11 owns containment and
termination; the N2 checkpoint delta owns safe restart. N4 registers the
distinct permission. N7/N10 reference the
dominant fence. N5 references the control surface. Existing N3/N6 audit contracts
remain authoritative; no new event or artifact family is introduced.
The normal hook required prose-lint cleanup in N4, N11, and the N2 delta;
those sentence splits preserve existing requirements and historical records.
The first automatic review found unconditional N7 exit-test wording. The
requirements now distinguish ordinary compensation from full-stop containment,
including guardrail abort and permanent-apply postcondition failure.
The second review tightened restart admission: old-runtime containment and
termination and the durable stop fence are confirmed before new-run effects.

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
