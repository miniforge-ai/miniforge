<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: hook fleet repository onboarding into PR trains

## Layer

Application + Adapter

## Depends on

- None

## Overview

Connect fleet-level repository onboarding to PR train state so Miniforge can
ingest and control external/open PRs from configured repositories.

## Motivation

To start dogfooding N9 external PR integration, Miniforge needs a direct path
from fleet repo configuration/discovery to train synchronization. Previously,
fleet controls were mostly placeholders and train list responses did not carry
enough state for dashboard views.

## What this adds

- Fleet repo onboarding API for list/add/discover/sync flows.
- Config-backed repo registry persisted under `~/.miniforge/config.edn` at `[:fleet :repos]`.
- GitHub CLI-backed discovery and open-PR sync into per-repo PR trains.
- Fleet UI actions wired to real backend endpoints.
- Full train payloads returned from list APIs so dashboard views can render complete train status.

## Changes in Detail

- `components/pr-train/src/ai/miniforge/pr_train/core.clj`
  - `list-trains` now returns full train maps (sorted by create time) rather than a reduced summary.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/trains.clj`
  - Added repo config readers/writers.
  - Added repo discovery via `gh api`.
  - Added sync logic to ingest open PRs into per-repo trains, update status/CI fields, and link dependencies.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/core.clj`
  - Added fleet sync bookkeeping state keys.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state.clj`
  - Re-exported fleet onboarding/sync functions.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/fleet.clj`
  - Added configured repo counts/details in fleet summary/state payload.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj`
  - Added handlers for fleet repo list/add/discover/sync.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server.clj`
  - Added routes:
    - `GET /api/fleet/repos`
    - `POST /api/fleet/repos/add`
    - `POST /api/fleet/repos/discover`
    - `POST /api/fleet/prs/sync`
- `components/web-dashboard/src/ai/miniforge/web_dashboard/views/fleet.clj`
  - Hooked Fleet buttons to real API calls (`+ Repo`, `Discover Repos`, `Sync PRs`).

## Testing Plan

- Run pre-commit checks:
  - `bb pre-commit`
- Verify full suite in hook path:
  - commit hook (`bb pre-commit`) passed end-to-end on this branch
- Spot-check functionality:
  - Fleet view supports adding repos, discovering repos, and syncing open PRs into trains.

## Deployment Plan

- Merge to `main`.
- After merge, create a fresh stable Polylith tag to reset changed-since
  baseline and avoid full-suite runs on every local test cycle.

## Related Issues/PRs

- Normative reference: `specs/normative/N9-external-pr-integration.md`
- PR branch: `codex/pr-train-repo-onboarding`

## Checklist

- [x] Fleet repo onboarding endpoints implemented
- [x] Fleet view wired to backend repo actions
- [x] PR train list returns full train state
- [x] Sync path creates/updates repo trains from open provider PRs
- [x] Pre-commit validation passed
- [ ] Reviewer validation in live dogfooding environment
