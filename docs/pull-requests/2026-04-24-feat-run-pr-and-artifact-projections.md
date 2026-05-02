<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: emit workflow-owned PRs and run artifact inventory

## Overview

Add the producer-side event and supervisory-state hooks that let downstream
consumers correlate workflow runs to their owned pull requests and emitted
artifacts.

This PR stays at the event and projection boundary. It does not change the
workflow FSM or phase-transition authority.

## Motivation

`miniforge-control` can now render a run dossier, but two remaining views were
still producer-limited:

- workflow runs could expose a summary `:workflow/pr-info`, but there was no
  canonical event stream entry that attached owned PRs back to the workflow run
- phase artifact output was emitted on workflow events, but supervisory-state
  did not retain a run-local inventory of those artifact ids

Without those producer hooks, the control-plane TUI had to fall back to honest
placeholders instead of real run-scoped PR and evidence context.

## Changes In Detail

- add canonical `:pr/created` event support to `event-stream`
- extend `workflow/completed` emission so workflow completion can carry:
  - a normalized collection of owned PRs
  - an optional workflow evidence-bundle id
- update `workflow.runner-events` to emit `:pr/created` for workflow-owned PRs
  inferred from release-phase and DAG completion context
- extend `supervisory-state` workflow runs with:
  - `:workflow-run/prs`
  - `:workflow-run/artifact-ids`
  - `:workflow-run/evidence-bundle-id`
- attach `:pr/workflow-run-id` to PR fleet entries when the producer knows the
  owning workflow run
- preserve that ownership on later `:pr/merged`, `:pr/closed`, and `:pr/scored`
  events so downstream correlation remains stable

## Scope And Non-Goals

This PR intentionally does not:

- modify the compiled workflow machine or phase-selection logic
- introduce evidence-bundle entity emission beyond the existing workflow-owned
  evidence-bundle id field
- attempt to infer run ownership for external PRs that were not produced by the
  workflow run

## Why This Shape

The producer already had summary-level PR data at workflow completion time. The
missing piece was a canonical fine-grained event and stable projection fields
that consumers could trust.

That makes the system cleaner in two ways:

- event producers remain the source of truth for workflow-owned PR correlation
- consumers no longer need to guess run ownership from global PR fleet rows

## Enabled Follow-On Work

This producer slice is meant to unlock two smaller PRs in `miniforge-control`:

1. tighten dossier and monitor PR correlation around `:pr/workflow-run-id`
2. surface run artifact and evidence inventory from workflow-owned artifact ids
   and evidence-bundle ids

## Testing Plan

Executed in `/tmp/miniforge-producer-hooks`:

```sh
git diff --check
bb test components/event-stream components/workflow components/supervisory-state
```

Result:

- `539` tests
- `2237` assertions
- `0` failures
- `0` errors

## Checklist

- [x] Producer emits canonical workflow-owned `:pr/created` events
- [x] Workflow runs retain artifact ids and owned PR summaries
- [x] PR fleet entries retain workflow ownership when known
- [x] Projection tests cover the new ownership and artifact inventory behavior
