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
   (each path read out of the environment and handed to the controller's `:persist!` store) and
   `cancel!`. Cancel stops the ledger admitting new work whatever the provider confirms; audit
   (artifact persistence) and release still run after cancel and after expiry.
3. The fake provider runs scripted exec (`:fake/exec-fn`), keeps files and records every command;
   `fake-commands` reads them through the interface.
4. `conformance`: `run-conformance` drives fresh controllers through the public lifecycle with
   synthetic tenants and grants and returns one result per check: duplicate acquire returns one
   environment, conflicting request refused, forged tenant refused (request and lease), grant
   required, stale lease refused after reacquire, a full lifecycle round trip, partial provisioning
   receipts (unrun when the provider supplies no failing fixture), and every receipt valid and
   unqualified. `:await-ready` lets a real provider poll readiness.

## Testing Plan

1. `work_test`: exec, files, scripted exec with stdin, artifact persistence, cancel stopping new
   work while persistence and release still run.
2. `conformance_test`: the fake passes every check; a check without its fixture is unrun; a
   provider that cannot provision fails the acquire-based checks, so the suite cannot pass a
   provider that refuses everything.

## Deployment Plan

Nothing deploys. The artifact store is a port the caller supplies; a durable store is later work.

## Related Issues/PRs

Issue #2439. Depends on #2449, #2447, #2445.

## Checklist

- [x] Work-inside calls with tests
- [x] Conformance suite passing on the fake
- [ ] CI green, Copilot threads resolved, squash-merged after the lifecycle PR
