<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Agent-worker controller, lifecycle and fake worker

## Overview

Second of three stacked PRs for the issue #2439 agent-worker contract (pilot slice D2): the worker
protocol, a controller that records each submission before the worker starts, the session-checked
lifecycle, and an in-memory fake worker. API surface class 1: in-process EDN.

## Layer

Application. Depends on the agent-worker contract PR.

## Motivation

Retries must reconcile with an existing session rather than launch another worker; an explicitly
selected worker that is missing or fails preflight must fail rather than be replaced; and a worker
that outlived its lease must not keep acting.

## Changes in Detail

1. `interface.protocol/AgentWorker`: `capabilities`, `preflight`, `start!`, `inspect`,
   `send-input!`, `request-cancel!`, `artifact-paths`, `usage`. Workers run every command through
   the environment lifecycle, so a stale lease stops them.
2. `controller`: the submission ledger, written with `swap-vals!` before the worker starts.
3. `lifecycle`: `start!` admits, preflights the selected worker (never another), records and
   starts; an identical resubmission returns the recorded session or start failure without a new
   preflight, or a retryable `start-in-flight` while the launch has not settled. Session calls check
   the session is the recorded one, its tenant and principal, that its worker is still registered
   (else `worker-unavailable`, with no other worker standing in), and that the lease is current; a
   new turn also needs a ready environment. Artifact collection skips the environment check, since the
   environment allows it after expiry. Steering rechecks the spend grant, needs inspection to show
   the turn ended, and reserves the turn so one caller starts it. Cancel is a request that
   `inspect` confirms; usage is normalized, and a worker's usage error is returned as is.
4. `fake`: in-memory worker that writes its input into the environment; with
   `:cancellation/none` it requests nothing.

## Testing Plan

Over the fake environment and fake worker: reconcile without relaunch, conflicting resubmission,
no substitution for a missing or failing worker (also for a recorded session whose worker is no
longer registered), stale lease for new attempts and old sessions,
spend-grant budget rules, unknown and partial usage, steering, cancel and artifacts, a worker
without steering, another tenant's or principal's session, a retry while starting or after preflight
starts failing, steering under an expired grant, an unsettled turn, an unready environment or
another caller's reservation, a worker without cancellation, a usage error, and artifacts after the
environment expires.

## Deployment Plan

Nothing deploys. The submission ledger is in memory, like the environment ledger.

## Related Issues/PRs

Issue #2439. Depends on the agent-worker contract PR.

## Checklist

- [x] Controller, lifecycle and fake worker with tests
- [ ] CI green, Copilot threads resolved, squash-merged
