<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add an exact-payload GitHub provider for OPSV

## Overview

Translate the governed coordinator's exact PR payload to the GitHub REST API.
This is a class-1 trusted runtime adapter; JSON exists only at the GitHub boundary.

## Motivation

The general release executor omits fields required by OPSV and cannot distinguish
an uncertain creation result from a confirmed failure. OPSV needs exact body,
draft, repository and revision semantics, including read-only reconciliation.

## Layer and dependencies

Adapter/infrastructure layer, stacked on the governed PR coordinator.
Runtime wiring follows both PRs. Merge the coordinator first.

## Changes in detail

- Check the remote branch's full object ID before creating a PR.
- Send the authorized title, body, base, head and draft flag without modification.
- Confirm returned provider fields before reporting success.
- Preserve uncertainty after an unconfirmed mutation; never retry creation.
- Reconcile through provider observation without adopting a mismatched PR.

## Testing plan

Use a command port with deterministic GitHub response fixtures; assert exact
arguments and stdin, refusal before POST, uncertain responses and reconciliation.
Run component tests, standards checks, lint, Polylith and the packaged CLI build.
Tests never create a real provider PR.

## Deployment plan

No external mode is enabled. Production must supply runtime-owned authority and
an exclusively controlled branch. GitHub PR creation is branch-based, not an
atomic compare-and-create by object ID; readback detects a moved head but cannot
undo an already created PR. Such outcomes must remain uncertain for reconciliation.

## Related issues/PRs

N7 governed actuation and the OPSV PR coordinator.
Wire behavior follows the [GitHub PR API](https://docs.github.com/en/rest/pulls/pulls)
and [GitHub CLI API transport](https://cli.github.com/manual/gh_api).

## Checklist

- [ ] Exact-payload and uncertain-outcome regression tests
- [ ] Standards, lint and build checks
- [ ] Review settled and CI green
