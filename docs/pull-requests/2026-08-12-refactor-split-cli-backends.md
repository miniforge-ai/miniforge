<!--
  Title: Split bases/cli/backends.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split backends.clj (rule 210)

## Overview

Splits the CLI backend module's resource-backed config and status/
validation logic out of `ai.miniforge.cli.backends` into two new
sibling namespaces, `ai.miniforge.cli.backends.config` and
`ai.miniforge.cli.backends.status`, resolving a stratum-lint SL003
finding (the combined namespace measured 7 real layers, over the rule
210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `backends.clj` (199 lines, dense layering for its size) was
next up.

## Changes in Detail

- New file `backends/config.clj`: `backend-config-resource`,
  `backend-config`, `backend-specs`, `backend-defaults` — the
  classpath-loaded config and its two derived maps. 3 layers.
  `backend-defaults` changed from `^:private` to public — it is now
  read cross-namespace by `backends.status/get-current-backend`,
  which is the only visibility change in this split.
- New file `backends/status.clj`: `availability-status`,
  `check-command-available?`, `get-current-backend`,
  `check-backend-status`, `get-backend-info`, `validate-backend` —
  status checks, backend-info assembly, and validation. 3 layers.
- `backends.clj`: kept `status-icon`, `format-backend-status`,
  `list-backends`, `print-backend-error`, `print-backends` — display
  formatting and the top-level listing/printing entry points. Now 3
  layers (down from 7), since the remaining functions only reference
  `backend-config/*` and `backend-status/*` (qualified, cross-namespace)
  rather than same-file symbols for the config/status pieces.
- `ai.miniforge.cli.config` (the CLI's `config` command, the one real
  external caller): `backends/backend-specs` → `backend-config/backend-specs`;
  `backends/validate-backend` and `backends/get-backend-info` →
  `backend-status/validate-backend` / `backend-status/get-backend-info`.
  `backends/print-backend-error` and `backends/print-backends` are
  unchanged — both stayed in the parent namespace, so the existing
  `backends` alias/require is still live and unchanged.
- `backends_test.clj`: the one test (`backend-specs-loaded-from-resource-test`)
  called `backends/backend-specs` and `backends/get-current-backend`
  directly — updated to `backend-config/backend-specs` and
  `backend-status/get-current-backend`, and the now-unused
  `ai.miniforge.cli.backends` require dropped.

This is pure code motion aside from the one required visibility change
(`backend-defaults`) and the call-site updates above — no behavior
changed.

## Testing Plan

- `stratum-lint` clean on all three touched/new source files (exit 0,
  was SL003 exit 1 on the original `backends.clj`).
- `clj-kondo` clean on all five touched files (0 errors, 0 warnings).
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.cli\.backends\b`, not a symbol-prefix guess) across
  `components/`, `bases/`, and `projects/` found exactly two real
  callers — `bases/cli/src/ai/miniforge/cli/config.clj` and
  `bases/cli/test/ai/miniforge/cli/backends_test.clj` — both updated
  above. No project-level caller.
- `clojure -M:poly check` — OK.
- Direct namespace test runs (not relying on `bb test` change-scope
  alone): `cd .worktrees/split-cli-backends && clojure -M:dev:test -e
  "(require 'ai.miniforge.cli.backends-test) (clojure.test/run-tests
  'ai.miniforge.cli.backends-test)"` — 1 test, 2 assertions, 0
  failures; same for `ai.miniforge.cli.config-test` — 6 tests, 34
  assertions, 0 failures.
- All five touched/new namespaces `require` cleanly under `:dev:test`
  (no compile errors).

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 remediation program's `bases/cli`
  batch (see PR #1772 `loader.clj`, PR #1766 `compiler.clj`, PR #1731
  `knowledge_safety.clj` for the established splitting convention this
  follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] clj-kondo clean on all touched files
- [x] `clojure -M:poly check` OK
- [x] Direct namespace test runs green (backends-test, config-test)
- [x] Adversarial self-review: def set unchanged except one
      `^:private` → public visibility flip, documented above
- [x] Zero project-level fan-in confirmed repo-wide before starting
