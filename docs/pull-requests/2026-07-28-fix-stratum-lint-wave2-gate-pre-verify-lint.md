<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: split gate/pre_verify_lint.clj to clear the 3-layer stratum budget (SL003, Wave 2)

## Overview

Splits `components/gate/src/ai/miniforge/gate/pre_verify_lint.clj` (107
lines, reporting 5 real layers) into three files, none over 3 layers:
technology detection, linter execution, and gate registration. This is a
genuine namespace split (Wave 2), not a mechanical relabeling — Wave 1
already cleared decorative `Layer N` mislabeling across this component
(`work/stratum-lint-baseline-2026-07-24.md`
/ `docs/pull-requests/2026-07-24-fix-stratum-lint-wave1-gate.md`), which
left `pre_verify_lint.clj` correctly labeled but still genuinely over
budget at 5 layers.

## Motivation

Rule 210 (`.cursor/rules/languages/clojure.mdc`,
`.cursor/rules/foundations/stratified-design.mdc`) caps a namespace at 3
layers; beyond that the file must be decomposed, not relabeled. The
pre-Wave-2 file mixed four concerns in one namespace:

1. File-extension → technology mapping (`ext->tech`, `file-extension`,
   `file->tech`, `detect-technologies`) — 3 layers on its own.
2. Worktree resolution + linter-violation → gate-error translation
   (`resolve-worktree`, `violation->lint-error`).
3. The check entry point (`check-pre-verify-lint`), which composes both
   of the above plus the repair stub.
4. The `defmethod`/`register-gate!` wiring into the gate registry.

Stacked into one namespace this reaches 5 real layers. No sibling gate in
this component runs a multi-file split (`syntax.clj`, `lint.clj`, etc. are
all single-file, ≤3 layers), but other components in this repo do use a
`<name>/<sub>.clj` subdirectory for a same-namespace-family split (e.g.
`agent-runtime/agent_runtime/error_classifier`,
`bb-dev-tools/bb_dev_tools/adapters`), so `pre_verify_lint/technology.clj`
and `pre_verify_lint/execution.clj` follow that repo-wide convention rather
than inventing a new one.

## Changes in Detail

- **`pre_verify_lint/technology.clj`** (new, `ai.miniforge.gate.pre-verify-lint.technology`)
  — `ext->tech`, `file-extension` (Layer 0), `file->tech` (Layer 1),
  `detect-technologies` (Layer 2). Unchanged logic, moved verbatim.
- **`pre_verify_lint/execution.clj`** (new, `ai.miniforge.gate.pre-verify-lint.execution`)
  — `resolve-worktree`, `violation->lint-error`, `repair-pre-verify-lint`
  (Layer 0, mutually independent), `check-pre-verify-lint` (Layer 1,
  composes Layer 0 plus `technology/detect-technologies` and
  `connector-linter.interface/run-all`). Unchanged logic, moved verbatim;
  only the `detect-technologies` call site now goes through the
  `technology` namespace alias instead of a same-file def.
- **`pre_verify_lint.clj`** — now only the `defmethod registry/get-gate
  :pre-verify-lint` and `(registry/register-gate! :pre-verify-lint)` side
  effect, at a single Layer 0. `:check`/`:repair` point at
  `execution/check-pre-verify-lint` and `execution/repair-pre-verify-lint`.
  This matches how this file's own docstring already described its role
  (self-registering gate, not a shared interface other gates require) and
  is the same shape every sibling gate file in this component uses for its
  own `defmethod`.
- **`interface.clj`** — untouched. It requires `ai.miniforge.gate.pre-verify-lint`
  only for the registration side effect; that require still transitively
  loads the two new namespaces, so no interface change was needed
  (confirmed: gates dispatch through the registry by keyword, and grepping
  the repo for `pre_verify_lint`/`pre-verify-lint` found no other direct
  requirer outside this component's own source, tests, and `bb.edn`'s
  classpath comment).
- Tests split to mirror the source: `pre_verify_lint/technology_test.clj`
  and `pre_verify_lint/execution_test.clj` carry the moved test bodies
  (now calling the public `detect-technologies`/`check-pre-verify-lint`/
  `repair-pre-verify-lint` directly — no more `var-get` private-var
  reach-around, since those functions now live in their own namespace and
  need to be public for `execution.clj`/`technology.clj` to use each
  other and to be exercised by their own test file).
  `pre_verify_lint_test.clj` now only asserts the wiring itself: the gate
  registers under `:pre-verify-lint` and `get-gate` resolves to
  `execution`'s exact check/repair functions.

No behavior change: same gate keyword, same check/repair semantics, same
error/violation shapes.

## Testing Plan

1. Stratum linter, non-warn mode, whole component:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/gate
   ```

   Before this change: 5 findings, including
   `pre_verify_lint.clj:97:1: SL003 file uses 5 distinct layers (max 3)`.
   After this change: that finding is gone; the file's own change
   introduces zero new findings. 4 pre-existing `SL003` findings remain in
   this component (`capabilities.clj`, `format.clj`, `policy_pack.clj`,
   `precommit_discipline.clj`) — confirmed present on `main` before this
   branch touched anything (stashed the diff and re-ran the same command),
   so they're separate, already-tracked Wave 2 targets, not something this
   change reintroduced.
2. `clj-kondo --lint components/gate`: 0 errors, 0 warnings, before and
   after.
3. `bb test` (full monorepo, stable-derived changed-and-affected —
   deliberately not `bb check:affected-tests`, since that only runs
   changed bricks and not dependents; this team has been burned by that
   gap before).
4. Adversarial self-review of the diff: confirmed no def lost its
   docstring in the move, no stale require, `ext->tech` and
   `violation->lint-error` kept their original `^:private`/`defn-`
   visibility (they'd briefly gone public in an earlier pass of this
   change and were reverted), and the `defmethod`/`register-gate!` side
   effect still fires at load time from the same file it always did.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — this is a structural split with the registration side effect
staying exactly where the registry/interface machinery expects it
(`pre_verify_lint.clj`, required from `gate/interface.clj`).

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md`
- Wave 1 (decorative fix, same component):
  `docs/pull-requests/2026-07-24-fix-stratum-lint-wave1-gate.md`
- Wave 1 tone/rigor reference (mechanical relabeling example):
  `docs/pull-requests/2026-07-25-fix-stratum-lint-wave1-fsm.md`

## Checklist

- [x] Stratum linter clean for the changed file (zero new/remaining
      findings vs. this file; 4 pre-existing unrelated findings confirmed
      present on `main`)
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] `ai.miniforge.gate.*` test namespaces run directly (118 tests, 351
      assertions, 0 failures/errors) plus the pre-commit hook's own
      331-test smoke suite on both commits, both green — full-monorepo
      `bb test` did not finish (Docker-dependent `dag-executor` retries
      unrelated to this change; see Testing Plan)
- [x] No behavior change: same gate keyword, same check/repair semantics
- [x] `interface.clj` confirmed unaffected (side-effect require still
      resolves transitively)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
