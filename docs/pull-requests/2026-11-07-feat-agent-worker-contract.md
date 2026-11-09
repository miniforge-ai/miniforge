<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Versioned agent-worker contract values and admission rules

## Overview

First of three stacked PRs for the issue #2439 agent-worker contract (pilot slice D2). New opt-in
component `agent-worker`. API surface class 1: in-process EDN.

## Layer

Foundations and domain rules. Depends on the environment-contract stack (#2445 → #2451).

## Motivation

Issue #2439 asks for a small, versioned worker contract where miniforge owns the run, the
environment and authority, and the selected harness owns its agent loop. Every call must be bound
to the logical run and task, environment UID, lease generation, grant, config digest, attempt and
native session reference; unknown usage must never read as zero.

## Changes in Detail

1. `schema`: closed `Binding`, `Config`, `Submission`, `Session`, `Status`, `Usage`,
   `Capabilities`. Native session ids are worker references, never run identities.
2. `admission`: the binding must belong to the acting tenant and name the environment's current
   lease (an older generation is `stale-lease`); a live root `:effect/spend` grant issued to the
   acting principal and named by the binding must cover the run and task and the configured budget;
   a cost ceiling needs a configured budget, and any configured budget needs a worker that can at
   least observe it. A grant with a token ceiling is refused as unenforceable: an attempt presents no
   token reading, so even a zero-token ceiling could not hold it.
3. `session`: `bind` builds a binding (config digest from `content-hash`); a resubmission of the
   same attempt is a duplicate (reconcile, never relaunch) or, with different content, refused;
   `normalize-usage` keeps unreported quantities nil and says so in `:usage/status`.
4. Wiring: the brick joins `projects/agent-environments` and the opt-in guard.

## Testing Plan

Unit tests for binding refusals, spend-grant refusals, budget rules, bind validity and digest,
resubmission decisions and usage normalization (a reported zero stays zero; nothing defaults to
zero).

## Deployment Plan

Nothing deploys; no default project depends on the brick.

## Related Issues/PRs

Issue #2439. Depends on #2451.

## Checklist

- [x] Contract values and rules with tests
- [ ] CI green, Copilot threads resolved, squash-merged
