<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: remove redundant Windows Clojure CLI install in CI

## Overview

Removes `cli: latest` from the Windows `setup-clojure` step in
`.github/workflows/ci.yml` and renames that step from "Install Clojure +
bb (Windows)" to "Install bb (Windows)" to match what it actually does.
Also tightens the comment on the following step, which is the one that
really provides `clojure`/`clj` on Windows.

## Motivation

This branch (`claude/elegant-austin-d616c5`) originally carried a fix for
an unused `column` binding in
`components/connector-linter/src/ai/miniforge/connector_linter/etl.clj`.
While this PR was open, `#1479` ("stratum-lint autofix for
components/connector-linter") merged to `main` and — independently,
addressing the same clj-kondo finding as part of its own review-comment
pass — removed that exact binding. After merging `main` into this branch,
`etl.clj` is byte-identical to `main`'s copy, so that change no longer
shows up in this PR's diff. Copilot's review correctly flagged the
mismatch between the PR writeup (which still described the `etl.clj`
change) and the actual diff (which no longer contained it). Rather than
force a no-op diff, this PR is retitled and rewritten to describe what's
actually left: a small, unrelated Windows CI cleanup that a prior review
round on this same branch produced and that still needs to land.

The CI cleanup itself: `setup-clojure`'s `cli: latest` option installs
the Clojure CLI as a PowerShell module on `windows-latest`, not a
`clojure.exe`/`clj.exe` binary. babashka's `p/process` shells out via
Java's `ProcessBuilder`, which cannot resolve PowerShell modules — so
any step invoking `clojure` through babashka never used that install in
the first place. The very next step in the same job already installs
`deps.clj`, which does provide a real `clojure.exe`/`clj.exe` on `PATH`.
`cli: latest` was therefore installing something inert. Removing it
changes nothing about what actually runs `clojure` on Windows CI.

## Changes in Detail

- `.github/workflows/ci.yml`:
  - Removed `cli: latest` from the Windows `setup-clojure` step.
  - Renamed that step from "Install Clojure + bb (Windows)" to "Install
    bb (Windows)" — it now only installs `bb`.
  - Reworded the comment above the `deps.clj` shim step to state directly
    that it's the one providing `clojure`/`clj`, instead of framing it as
    filling in "one missing piece" alongside a `cli: latest` install that
    no longer exists.

## Testing Plan

1. `Test (Windows, bb-platform unit tests)` CI job green on this branch
   with `cli: latest` removed (confirms nothing on Windows CI depended on
   it).
2. Read the full `ci.yml` diff — confirmed the only behavioral change is
   dropping the redundant `cli: latest` install; `deps.clj` install logic
   is untouched.

## Deployment Plan

Merges to `main`. Windows CI only; no other job affected. Nothing to
roll out or monitor beyond watching the next few Windows CI runs stay
green.

## Related Issues/PRs

- Supersedes this branch's original scope (the `connector-linter`
  `column` binding fix), which merged separately via `#1479`.

## Checklist

- [x] Windows CI job green with the change in place
- [x] Full diff read; confirmed mechanical (step rename + comment reword
      + one config line removed)
- [x] PR writeup rewritten to match the actual diff after `#1479` merged
      the originally-described change independently
