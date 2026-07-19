<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor(adapter-claude-code): externalize session staleness windows to EDN

## Overview

Moves the three hardcoded session staleness windows in `adapter-claude-code`
into a single EDN resource, in line with config-as-data (Dewey 007). Behavior
is unchanged: the loaded integers equal the previous inline expressions.

## Motivation

Two source files carried staleness windows as inline arithmetic literals:

- `discovery.clj` had `activity-threshold-ms (* 5 60 1000)` — the
  session-liveness window.
- `impl.clj`'s `poll-status` had `(* 60 1000)` (running-vs-idle) and
  `(* 5 60 1000)` (running-vs-dead) inline at the call sites.

These are tuning values, not algorithm constants, so they belong in data. The
component already loads `tool-profiles.edn` from
`resources/config/adapter_claude_code/`, so the loading pattern already exists
here.

## Changes

- New resource `resources/config/adapter_claude_code/staleness.edn` — one
  non-namespaced map with the three windows as computed integers
  (`{:session-activity-window-ms 300000 :running-window-ms 60000
  :idle-window-ms 300000}`), each with a `;; N min` comment. Apache-2.0 header
  copied verbatim from the existing reliability defaults exemplar.
- `discovery.clj` loads the map once at namespace load via `io/resource` +
  `edn/read-string` into `staleness-windows`, and sources
  `activity-threshold-ms` from `:session-activity-window-ms`.
- `impl.clj`'s `poll-status` references
  `(:running-window-ms discovery/staleness-windows)` and
  `(:idle-window-ms discovery/staleness-windows)` in place of the inline
  literals.

## Verification

- Load smoke: loaded windows equal the original expressions —
  `activity-threshold-ms` = `(* 5 60 1000)`, `:running-window-ms` =
  `(* 60 1000)`, `:idle-window-ms` = `(* 5 60 1000)`.
- Isolated component tests: 21 tests, 68 assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.
