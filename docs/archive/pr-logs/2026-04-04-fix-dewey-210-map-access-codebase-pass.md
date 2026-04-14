<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Dewey 210 map-access standards pass across entire codebase

## Overview

Comprehensive application of the Dewey 210 map-access rule
(`(get m :k default)` over `(or (:k m) default)`) across all 45 source
files and 1 test file that contained the pattern. Also fixes two
pre-existing parallel-test-runner race conditions exposed when the
expanded changed-brick set ran more bricks concurrently.

## Motivation

The previous standards pass (merged in `4dbc4f0`) covered only files
changed as part of in-flight PRs. This pass extends the rule to the full
codebase. Per Dewey 210:

> `(get m :k default)` is preferred over `(or (:k m) default)` for
> single-key map lookups with a literal default.  `get` is explicit about
> what "default" means (key absent), whereas `or` is truthy-based and can
> mask an intentionally `nil` value stored at the key.

## Changes in Detail

### Dewey 210 source fixes (45 files)

Every `(or (:k m) literal)` pattern replaced with `(get m :k literal)`
across bases and components. Preserved patterns:

- **Dual-key coalesces** — `(or (:k1 m) (:k2 m) literal)` unchanged;
  `get` cannot express multi-key fallback in one call.
- **Explicitly-nil JSON fields** — `(or (:type d) "choice")` kept as `or`
  in `server/control_plane.clj` and `views/control_plane.clj` because
  JSON deserialisation can produce `{:type nil}` (key present, value nil);
  `get` returns nil in that case, `or` returns the default correctly.
- **Computed defaults** — `(or (:k m) (atom {...}))` patterns kept as
  `or` since `get` eagerly evaluates the default expression.

### Test isolation fix

`components/web-dashboard/test/.../workflows_test.clj`:
`get-workflows-exposes-stream-preview-and-metrics-test` now wraps
`get-workflows` in `with-redefs [sut/events-dir-path (.getPath events-dir)]`
pointing at a fresh temp directory. Without this, the 77+ workflow event
files in `~/.miniforge/events/` ranked ahead of the test's in-memory
workflow (March 28 timestamps) under the `max-recent-workflows=50` sort,
causing the filter to return nil and all five assertions to fail.

### Parallel test runner race fixes

`scripts/test-changed-bricks.bb` — two new affinity groups added to
`affinity-groups`:

- **`workflow+phase`**: `workflow` tests (runner_extended_test) call
  `ensure-phase-implementations-loaded!` which dynamically `require`s
  `phase.implement` → `context_pack.interface`. When `phase` tests run
  in a concurrent pmap group and also trigger that load path,
  `Compiler$CompilerException` races appear in `context_pack/interface.clj`.
  Running them sequentially eliminates the race.

- **`pr-sync+mcp`**: `pr-sync` fleet_parallel_test uses
  `with-redefs [clojure.java.shell/sh ...]` returning GitHub PR JSON.
  `mcp-artifact-server` context_cache tests call `shell/sh` for file
  globbing. When the two groups run in parallel the `with-redefs`
  mutation bleeds into the glob handler and context-glob-no-match
  assertions fail with PR JSON instead of "No files matched".

## Testing Plan

- [x] `bb pre-commit` — lint + GraalVM compat + 2584 tests, 0 failures
  (three consecutive clean runs after affinity fixes)
- [x] Verified each changed file compiles and loads cleanly
- [ ] Spot-check a few of the changed map-access sites in a REPL session

## Deployment Plan

Merge as a standards-only fix. No runtime behaviour changes; `(get m :k v)`
is semantically identical to `(or (:k m) v)` for all cases covered
(non-nil values, absent keys). No migration required.

## Related Issues / PRs

- Partial predecessor: `4dbc4f0` (previous session's in-flight-files-only pass)
- Standards reference: `.standards/languages/clojure.mdc` (Dewey 210)

## Checklist

- [x] PR doc added under `docs/pull-requests/`
- [x] All 45 source files reviewed; semantic edge cases preserved
- [x] Test isolation bug fixed (`workflows_test.clj`)
- [x] Two parallel-runner races fixed (`test-changed-bricks.bb`)
- [x] Pre-commit passes (lint + GraalVM + full test suite)
