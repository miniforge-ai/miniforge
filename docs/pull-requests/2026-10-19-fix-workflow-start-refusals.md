<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stop execution when workflow-start publication is refused

## Overview

Treat current-journal workflow-start refusal as a failed execution before any
phase runs. Keep startup publication inside the runner's cleanup boundary.

## Motivation

N7 orchestration must not execute effects after its authoritative lifecycle
journal refuses admission. Logging a warning is not acknowledgement.

## Layer and dependencies

Application integration. Base: refreshed `main` at `26bbbd589`.
Depends on #2058 (explicit workflow-start intent), now merged and incorporated.
Workflow-completion refusal, default-host
migration, and gate acknowledgement remain separate changes.

## Changes in detail

- Propagate start publication anomalies into the execution failure context.
- Persist the failed terminal snapshot and run existing cleanup.
- Preserve nil, legacy, and explicitly skipped lifecycle behavior.
- Extract the existing pipeline exception conversion into named boundary functions,
  retaining dashboard-stop and general failure behavior without expanding the runner.

## Testing plan

Actual runner with current journals: closed journal, missing intent, construction
exception, zero phase-entry effects, failed disk checkpoint, and cleanup.
Retain accepted, nil, legacy, and skipped lifecycle cases. Run workflow consumers
serially, build CLI, test the isolated package, and audit standards before push.

## Local verification

- All three workflow/phase consumers pass, serially (1m40s), after main alignment.
- CLI rebuilt: 39,188,620 bytes; isolated package: 96 tests, 349 assertions passed,
  including constructor-boundary and merged terminal-phase coverage without
  production source overlays.
- Kondo reports no warnings/errors; changed namespaces match inferred strata.
- Previous CLI reproduced phase effects after missing intent and constructor
  failure, and missing cleanup after fatal startup failure. Checkpoint assertions
  use the store's existing instant-string normalization contract.
- One publisher correction and one runner integration commit stay within normal
  budgets (31 and 198 reportable lines). No additional budget exception.

Terminal publication acknowledgement and terminal-snapshot re-publication are
separate follow-up work, including authoritative FSM state on refusal. These tests
prove active-run start admission; they are not default-host or N7 completion claims.

## Deployment plan

Normal CLI/package deployment. Does not enable the current journal by default.

## Related issues/PRs

PR #2058, N7 implementation completion, N3 current-event publication contract.

## Checklist

- [ ] Regression and package verification
- [ ] Adversarial standards pass and normal signed commit hooks
- [ ] Exact-head review, all CI checks, and resolved comments before merge
