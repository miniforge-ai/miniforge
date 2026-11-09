<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Agent-environment exec, files, artifacts, cancel and the provider conformance suite

## Overview

Last of four stacked PRs for the issue #2439 environment contract (pilot slice D1): work inside a
leased environment, and the shared contract suite every environment provider must pass. API
surface class 1: in-process EDN. Every receipt is `:profile/unqualified`.

## Layer

Application and qualification. Depends on the lifecycle PR (`feat/agent-environment-lifecycle`).

## Motivation

Issue #2439 lists exec, copy and artifact persistence among the lifecycle calls, and asks for one
contract suite that any provider (the fake now, Kubernetes next) runs unchanged, with unrun checks
reported as unrun rather than passed.

## Changes in Detail

1. Provider protocol gains `exec!`, `write-file!`, `read-file`, `cancel!`. `Command` gains an
   optional `:exec/stdin`.
2. Lifecycle gains `exec!`, `write-file!`, `read-file` (receipted as copy), `persist-artifacts!`
   (each path read out of the environment and handed to the controller's `:persist!` store, one
   receipt step per path, so a partial persist is receipted partial) and `cancel!`. File paths
   must be absolute. Cancel stops the ledger admitting new work before the provider is asked;
   audit (artifact persistence) and release still run after cancel and after expiry.
3. The fake provider runs scripted exec (`:fake/exec-fn`), keeps files and records every command;
   `fake-commands` reads them through the interface. A write or an exec registers against the
   target's UID in one atomic step, and a cancel while an exec runs is uncertain.
   `:fake/no-steps?` answers cancel and delete with no steps.
4. A provider answer without steps, such as an anomaly, confirms nothing: provision, cancel,
   release and cleanup read it as one uncertain step, so it is never receipted as applied.
5. `conformance`: `run-conformance` drives fresh controllers through the public lifecycle with
   synthetic tenants and grants and returns one result per check: duplicate acquire returns one
   environment, conflicting request refused, forged tenant refused (request and lease), grant
   required, stale lease refused after reacquire, a full lifecycle round trip (an exec must return
   its exit, stdout and stderr), partial provisioning
   receipts (unrun when the provider supplies no failing fixture), every receipt valid and
   unqualified, and cleanup confirmed: every lease a check acquired is released, also when the
   check throws, and every ledger entry ends released. A check that throws, such as a readiness
   hook timing out, is that check failed with what it threw, and the other checks still run.
   `:await-ready` lets a real provider poll readiness.

## Testing Plan

1. `work_test`: exec, files, absolute paths, scripted exec with stdin, artifact persistence and a
   partial persist, cancel stopping new work while persistence and release still run, a cancel
   during an exec, an exec outliving its environment, the fake touching only its own namespace, and
   cancel and release answered without steps (uncertain, and the key stays cleanup-uncertain).
2. `conformance_test`: the fake passes every check; a check without its fixture is unrun; a
   provider that cannot provision fails the acquire-based checks (forged tenant included), so the
   suite cannot pass a provider that refuses everything; an exec without its output fails the round
   trip; a readiness hook that throws fails only the checks that use it; unconfirmed cleanup and a leak with no lease
   fail the cleanup check.

## Deployment Plan

Nothing deploys. The artifact store is a port the caller supplies; a durable store is later work.

## Related Issues/PRs

Issue #2439. Depends on #2449, #2447, #2445.

## Checklist

- [x] Work-inside calls with tests
- [x] Conformance suite passing on the fake
- [ ] CI green, Copilot threads resolved, squash-merged after the lifecycle PR
