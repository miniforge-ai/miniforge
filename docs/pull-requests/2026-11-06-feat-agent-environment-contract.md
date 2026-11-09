<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Versioned agent-environment contract values and lease rules

## Overview

First step of #2439 (opt-in development-agent environments): versioned values shared by every environment provider and controller.
Pure rules decide admission and leases.
API surface class 1: in-process EDN. Nothing here provisions anything.

## Layer

Foundations and domain rules. Later PRs add receipts, refusals and the ledger controller (D1b).
Then comes the lifecycle over a fake provider (D1c).
D1d adds exec, file copy, artifact persistence, cancel and the shared provider conformance suite.

## Motivation

Issue #2439 needs one tenant-bound environment contract before any provider (fake, Kubernetes) exists.
It binds tenant, principal, run, task, environment UID, namespace, lease generation, policy and template hashes,
resource limits and expiry.
It refuses forged tenants, conflicting requests and stale leases. The rules are pure so a durable ledger can replace the
in-memory one later without
changing them.

## Changes in Detail

1. New component `agent-environment`:
   1. `schema`: closed, versioned `Request`, `Lease`, `Environment`, `Capabilities`, `Receipt`,
      `Command`; isolation levels `shared-kernel` / `dedicated-kernel`; the only receipt profile is
      `:profile/unqualified`.
   2. `admission`: the acting tenant and principal must be the ones the request names.
      A live root `:effect/provision` grant issued to the acting principal must cover the run and task (reuses `execution-grant/authorize`).
      Declared capabilities must offer the requested isolation and limits, with no weaker substitute.
      An expired request is refused.
   3. `lease`: one environment per tenant/run/task key.
      Acquire is new, duplicate or refused (conflicting request, in flight, cancelled, cleanup uncertain).
      The generation only grows; a lease that is not the entry's current one is stale.
      After expiry only cleanup and audit calls run.
      Namespaces are derived from the key and are valid DNS labels.
2. New project `projects/agent-environments` so CI tests the brick; registered in `workspace.edn`
   and the root `:dev`/`:test` paths.
3. `development/test/opt_in_profile_test.clj`: `projects/miniforge`, `projects/miniforge-core` and
   `projects/data-foundry` reach no opt-in brick, directly or through any brick's `:local/root`
   dependencies.

Reuse: `execution-grant` for authority, `content-hash` for namespace derivation, the `tenancy`
acting-context shape for identity. `effect-transaction` and `file-durability` come in with the
controller and durable ledger; `run-owner-lock` with durable leases.

## Testing Plan

1. Unit tests for every acquire decision, lease refusal, expiry, namespace derivation, identity,
   grant and capability check, and schema closure.
2. The opt-in guard test runs under `bb test:dev`.
3. `clj-kondo`, stratum-lint and `poly check` clean.

## Deployment Plan

Nothing deploys. No default project depends on the new brick; the guard test enforces it.

## Related Issues/PRs

Issue #2439 (pilot slice D1). Generated receipts are unqualified; no isolation is claimed.

## Checklist

- [x] Contract values and pure rules with tests
- [x] Opt-in project and default-project guard
- [ ] CI green, Copilot threads resolved, squash-merged
