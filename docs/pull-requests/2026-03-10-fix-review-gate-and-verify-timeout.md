# fix: Review Gate, Verify Timeout, and TUI PR Decomposition Wiring

**Branch:** `fix/review-gate-and-verify-timeout`
**Base:** `main`
**Date:** 2026-03-10
**Layer:** Application / TUI integration
**Depends On:** None

## Overview

Updates the existing review-gate / verify-timeout PR branch with the missing
`tui-views` GitHub persistence and PR decomposition wiring that had stalled in
the implement loop.

The branch now contains:

- the original gate fix so approved reviews satisfy the review gate
- the verify fix so timeout/rate-limit failures do not loop back into implement
- the `tui-views` follow-up that fetches PR diffs/details through `gh` and wires
  `handle-decompose-pr` and `:fetch-pr-diff` into the Elm-style update loop

## Motivation

The TUI had stubbed or incomplete behavior around PR decomposition:

- there was no concrete GitHub persistence namespace for `gh pr diff` and
  `gh pr view --json`
- `handle-decompose-pr` was not wired to fetch real PR context and invoke the
  decomposition component
- the message/update contract for decomposition and PR diff fetching was not
  consistent across handlers and tests
- chat-triggered actions could surface `:msg/side-effect-error` directly
  instead of a chat-friendly result when a delegated handler failed

This left the decomposition flow unreviewable and broke a large portion of the
generated `tui-views` acceptance coverage.

## Changes In Detail

### TUI GitHub persistence

- Added `components/tui-views/src/ai/miniforge/tui_views/persistence/github.clj`
- Wrapped `gh pr diff` and `gh pr view --json` with precondition checks
- Standardized on `babashka.process/shell`
- Added number coercion helper and concurrent diff/detail fetches
- Gracefully return `nil` fields on CLI failure, parse failure, or exception

### TUI handler wiring

- Replaced the `handle-decompose-pr` stub with a real implementation
- Fetches diff/detail, derives changed file paths, resolves
  `ai.miniforge.pr-decompose.interface/decompose` via `requiring-resolve`, and
  emits `:msg/decomposition-started`
- Added `handle-fetch-pr-diff`
- Routed `:fetch-pr-diff` in `dispatch-effect`
- Normalized `handle-chat-execute-action` so delegated handler failures become
  `:msg/chat-action-result` for chat consumers

### Message and reducer contracts

- Flattened `decomposition-started` payloads so plan fields live at the top
  level beside `:pr-id`
- Added `pr-diff-fetched` constructor with `[pr-id diff detail error]`
- Updated decomposition reducer expectations to consume the flattened payload

### Test coverage

- Added and aligned `tui-views` acceptance, integration, contract, and handler
  tests around:
  - GitHub persistence wrappers
  - PR diff fetch effect routing
  - decomposition flow behavior
  - chat action error handling
  - message contract shape

## Testing Plan

- [x] Targeted `tui-views` namespaces covering GitHub persistence, decomposition,
      events, and interface context pass
- [x] `bb scripts/test-changed-bricks.bb` passes with `888` tests and `0`
      failures / `0` errors
- [ ] Manual TUI smoke test against a real PR

## Deployment Plan

No special deployment steps.

This is application/TUI logic plus tests. It ships with the normal deploy path.

## Related Issues/PRs

- Existing PR: #288
- Related branch work already present in this PR:
  - review gate uses `:review/decision`
  - verify timeout/rate-limit avoids implement-loop redirects

## Checklist

- [x] PR doc added
- [x] GitHub persistence namespace added
- [x] decomposition handler wired
- [x] PR diff fetch effect wired
- [x] message/update contract aligned
- [x] tests passing
