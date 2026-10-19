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

Application integration. Base: refreshed `main` at `f1b83e973`.
Depends on #2058 (explicit workflow-start intent); incorporate that reviewed
dependency before implementation. Workflow-completion refusal, default-host
migration, and gate acknowledgement remain separate changes.

## Changes in detail

- Propagate start publication anomalies into the execution failure context.
- Persist the failed terminal snapshot and run existing cleanup.
- Preserve nil, legacy, and explicitly skipped lifecycle behavior.

## Testing plan

Actual runner with current journals: closed journal, missing intent, construction
exception, zero phase-entry effects, failed disk checkpoint, and cleanup.
Retain accepted, nil, legacy, and skipped lifecycle cases. Run workflow consumers
serially, build CLI, test the isolated package, and audit standards before push.

## Deployment plan

Normal CLI/package deployment. Does not enable the current journal by default.

## Related issues/PRs

PR #2058, N7 implementation completion, N3 current-event publication contract.

## Checklist

- [ ] Regression and package verification
- [ ] Adversarial standards pass and normal signed commit hooks
- [ ] Exact-head review, all CI checks, and resolved comments before merge
