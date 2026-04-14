<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: full tui implementation (repo manager, browse/sync, level-local navigation)

## Layer

Application + Adapter

## Depends on

- None

## Overview

Completes the Miniforge TUI implementation for top-level navigation,
command-mode completion, repo management, and provider-backed PR sync.

This PR turns the TUI into a cohesive operational interface with:

- robust view abstraction boundaries,
- level-local keyboard navigation (1-0 by abstraction level),
- first-class repository management from a dedicated Repos screen,
- GitHub + GitLab browse flows,
- GitHub PR + GitLab MR sync into PR Fleet,
- and improved light-theme readability.

## Motivation

The previous state had partial placeholders and inconsistent interaction semantics:

- top-level Tab could leak into stale detail state,
- number-key mapping mixed abstraction levels,
- repo onboarding relied heavily on command strings instead of screen-native interactions,
- browse/sync behavior was inconsistent across providers,
- and light-theme active-tab contrast could become unreadable.

## What This Adds

- Fixed navigation state bug where stale detail context hijacked top-level Tab flow.
- Added Shift+Tab reverse cycling across views and completion menus.
- Added command completion system with popup navigation/accept semantics.
- Added dedicated Repos top-level view with table, selection, search, browse/fleet modes, and remove confirmation flow.
- Added provider-backed browse:
  - GitHub (viewer repos + org repo traversal/fallbacks)
  - GitLab (membership and group project browsing)
- Added GitLab repository slug support (`gitlab:group/subgroup/repo`) in fleet config.
- Added GitLab MR ingestion to sync path so PR Fleet reflects both GitHub PRs and GitLab MRs.
- Added level-local number navigation model (`1-0`) so key mappings are consistent by abstraction level.
- Added/updated help/footer copy to reflect key-driven flows (`r` refresh/sync, repo-screen management).
- Fixed active-tab contrast by using theme-selected fg/bg for highlights (light theme readability).

## Changes In Detail

- `components/tui-engine/*`
  - Extended key normalization (Shift+Tab, number keys incl `0`, and interaction keys used by Repos view).
  - Updated screen/input handling and tests for new keyboard pathways.
  - Expanded style/theme handling and active-highlight rendering compatibility.

- `components/tui-views/src/ai/miniforge/tui_views/model.clj`
  - Extended model for repo manager state (`fleet`, `browse` cache/source/loading).
  - Added helper functions for repo manager visible items and candidates.

- `components/tui-views/src/ai/miniforge/tui_views/update/*.clj`
  - `navigation.clj`: fixed stale detail context handling; added reverse cycling; level-aware view helpers.
  - `update.clj`: wired Shift+Tab, level-local `1-0` navigation, Repos screen actions, and refresh/sync behavior.
  - `completion.clj` + `command.clj`: command/arg completion system, browse sentinel handling, completion side-effects.
  - `events.clj`: browse/discover/sync result handling, completion refresh, and error-state cleanup.
  - `mode.clj` + `selection.clj`: consistent search/selection semantics across aggregate screens including Repos.

- `components/tui-views/src/ai/miniforge/tui_views/views/*.clj`
  - Added `repo_manager.clj` and shared `tab_bar.clj`.
  - Updated PR Fleet, Workflow List, DAG Kanban, Help, and related screens with new key hints and numbering.
  - Improved tab highlight rendering to use theme-selected colors.

- `components/tui-views/src/ai/miniforge/tui_views/interface.clj` + `persistence.clj`
  - Added browse side-effect plumbing and provider-aware browse path.
  - Startup model loading now includes fleet repos.

- `components/pr-sync/src/ai/miniforge/pr_sync/core.clj`
  - Added provider-aware browse (`:github`, `:gitlab`, `:all`).
  - Added GitLab repo slug validation/normalization support.
  - Added GitLab MR fetch + normalization into TrainPR shape.
  - Updated fleet sync to aggregate GitHub + GitLab open work items.

- `components/pr-sync/src/ai/miniforge/pr_sync/interface.clj`
  - Exposed provider-aware browse interface updates.

- Tests
  - Added substantial new coverage for completion flows, selection/search behavior,
    repo-manager actions, input normalization, navigation regressions, and provider
    sync/browse scenarios.

## Testing Plan

Executed repeatedly during implementation:

- Focused Clojure suites:
  - `clojure -M:dev:test -e "(require 'clojure.test ... ) (clojure.test/run-tests ...)"`
  - Latest focused run: `73 tests, 490 assertions, 0 failures, 0 errors`
- Build/install:
  - `bb build:tui`
  - `bb install:tui`

## Deployment Plan

- Merge to `main`.
- Relaunch `miniforge-tui` after install to pick up the updated runtime/binary.

## Related Issues/PRs

- Branch: `feat/full-tui-implementation`
- Normative references already in repository for N9 external PR integration and TUI operation.

## Checklist

- [x] Navigation abstraction boundary bug fixed
- [x] Shift+Tab reverse cycles implemented
- [x] Command completion system added
- [x] Repos screen implemented as first-class aggregate view
- [x] Browse supports GitHub + GitLab
- [x] Sync aggregates GitHub PRs + GitLab MRs
- [x] Level-local number navigation (`1-0`) implemented
- [x] Light-theme active-tab contrast fixed
- [x] Focused tests passing
- [x] Build/install successful
- [ ] Reviewer validation in live dogfooding flows
