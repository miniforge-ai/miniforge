<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Agent-environment receipts, refusals, ledger controller and provider protocol

## Overview

Second of four stacked PRs for the issue #2439 environment contract (pilot slice D1): the pieces
the lifecycle is built from. API surface class 1: in-process EDN.

## Layer

Application foundations. Depends on the D1a PR (`feat/agent-environment-contract`). The next PR
drives these through the lifecycle over a fake provider.

## Motivation

Leases, refusals and receipts are miniforge's to decide, once, for every provider. Providers only
supply compute and report what they did, so their protocol carries no tenancy, lease or authority.

## Changes in Detail

1. `interface.protocol/EnvironmentProvider`: `capabilities`, `probe`, `provision!`, `observe`,
   `deprovision!`. Providers return data; a delete that was accepted but not confirmed is
   `:outcome/uncertain`, never applied.
2. `receipt`: one factory; the profile is always `:profile/unqualified`; a refusal is receipted to
   the acting tenant, not the tenant a forged request names. `steps-outcome` summarizes provider
   steps: a failure after anything may have taken effect is partial.
3. `refusal`: each reason's anomaly type (authority failures `:unauthorized`, lease and state
   conflicts `:conflict`, expired request `:invalid-input`, preflight `:unsupported`, anything
   else `:fault`) and its system message.
4. `controller`: the ledger atom, clock and receipt sink. `claim!` applies the acquire decision
   with `swap-vals!`, so two concurrent acquires of one key cannot both provision. `settle!` moves
   an entry only while the presented lease is still current, so a stale holder cannot move a
   replacement. `refuse!` and `finish!` emit the receipt before returning.
5. System message catalog for every reason.

## Testing Plan

Unit tests cover step summaries, receipt validity and ownership, reason types and catalog coverage.
Every reason has a message, not its bare key name.
Tests also cover single reservation under a second claim and settle refusing a stale lease.
Receipts must be emitted before refusals and results return.

## Deployment Plan

Nothing deploys; no default project depends on the brick.

## Related Issues/PRs

Issue #2439. Depends on the D1a PR.

## Checklist

- [x] Receipts, refusals, controller and protocol with tests
- [ ] CI green, Copilot threads resolved, squash-merged after D1a
