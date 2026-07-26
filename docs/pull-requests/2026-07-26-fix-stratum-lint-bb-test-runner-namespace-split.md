<!--
  Title: Split bb-test-runner into 7 namespaces to resolve SL003 (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: split bb-test-runner into 7 namespaces to resolve SL003 (Wave 1)

## Overview

`components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.cljc`
reported `stratum-lint`'s `SL003`: 5 distinct layers (0-4) against the
3-layer budget. This finding was surfaced — and deliberately deferred —
by PR [#1526](https://github.com/miniforge-ai/miniforge/pull/1526),
which restructured the file's only `SL008`-shaped reader conditional
(`classpath-test-roots`) and, as an unavoidable side effect of that
being the file's first `--fix` touch, triggered a full restratification
that exposed the true layer count for the first time. #1526 explicitly
deferred the split to "bb-test-runner's own future Wave 1 PR." This is
that PR.

Unlike the mechanical `--fix`-only Wave 1 PRs (`repo-dag`, `knowledge`,
`llm`, etc.), `stratum-lint`'s own `SL003` message offers two remedies —
"split the namespace or extract a component" — and 5 real layers means
the file's concerns are genuinely too deep for one namespace, not just
mislabeled. `core.cljc` was split by hand into 7 focused namespaces,
none exceeding 3 layers, plus the pre-existing `interface.clj` (updated
to dispatch to the new namespaces instead of `core`, kept as one flat
layer as before).

## Motivation

Confirmed the baseline with a fresh plain-lint run before touching
anything:

```text
components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.cljc:520:1: SL003 file uses 5 distinct layers (max 3); split the namespace or extract a component
```

Reconstructing the file's same-file reference graph by hand (not just
trusting the task's suggested concern groupings — verified against
actual same-file calls) surfaced one incorrect assumption worth calling
out: the pre-split namespace docstring described `path-segments`,
`test-segment?`, `resource-segment?`, `test-path?`, and `resource-path?`
as "path and deps helpers" alongside test discovery. In fact none of
them are used by `discover-test-namespaces` (which walks the classpath
directly via `fs/glob`) — their *only* caller, anywhere in the file, is
`classify-coverage-paths`. They are Cloverage path-classification
helpers that happened to live next to test discovery, not test
discovery helpers. This is why `coverage-paths` below is its own
namespace rather than folded into `test-discovery`.

The remaining depth came from two independent dependency spines that
each needed a real cut, not just relabeling:

1. **Coverage build chain** (depth 4): `normalized-alias-keys` →
   `merge-deps-config` → `build-coverage-sdeps` → `coverage-args` →
   `run-coverage`. Any single namespace containing more than 3
   consecutive links of this chain reports `SL003` again — confirmed
   experimentally (an intermediate draft that kept `merge-deps-config`
   inside the coverage-command namespace pushed `coverage-args` to
   Layer 3). Resolved by cutting the chain into three namespaces
   (`deps-config`, `coverage-cmd`, `coverage-exec`), each holding a
   short enough consecutive run.
2. **Diagnostic plan chain** (depth 3): `default-expand-start-size` →
   `positive-start-size` → `expand-project-groups` →
   `diagnostic-test-plan`. Same shape: keeping `expand-project-groups`
   and `diagnostic-test-plan` in the same file, even with everything
   else extracted, still reports 4 layers. Resolved by moving the
   ordering/grouping algorithms (`shuffle-projects`, `order-projects`,
   `positive-start-size`, `expand-project-groups`, `bisect-project-
   groups`) into `stable-derived` and leaving `diagnostic-plan` with
   only the CLI-arg-parsing and plan-assembly orchestration, whose
   dependencies on the grouping algorithms are then cross-namespace
   calls invisible to the same-file layer count.

## Changes in Detail

`core.cljc` (44 top-level defs, 5 layers) is deleted and replaced with
7 files, each independently ≤ 3 layers (confirmed by plain `stratum-
lint` per file and over the whole component; `--fix` is a no-op on
every file except `interface.clj`, which only gained `^{:stratum n}`
metadata):

- **`test_discovery.cljc`** (3 layers) — `path->ns-symbol`,
  `discover-test-namespaces`, `classpath-test-roots`, `run-all`. Keeps
  the `run-all`/`classpath-test-roots` Babashka-only pairing together,
  as directed — the reader-conditional stays inside the function body
  (`:default`, not `:clj`), matching the shape #1526 established. This
  is the only `.cljc` file in the split; every other namespace is pure
  `.clj`.
- **`coverage_paths.clj`** (3 layers) — `path-segments`,
  `test-segment?`, `resource-segment?`, `test-path?`, `resource-path?`,
  `classify-coverage-paths`. Fully self-contained; no cross-namespace
  requires. `test-path?`/`resource-path?` stay private (`defn-`) — they
  were never referenced outside this same-file cluster even before the
  split, so making them public would have been an unforced API
  addition.
- **`stable_derived.clj`** (3 layers, 20 defs) — the largest file:
  project-selector parsing/formatting, stable-tag detection, the
  Polylith changed-projects `ws` queries, git-worktree env
  sanitization, heartbeat-interval derivation, and project
  ordering/shuffling/additive-expand/breadth-first-bisect grouping.
  Two otherwise-unconnected concerns (selector/tag/env/heartbeat
  helpers, and the grouping algorithms) share this namespace rather
  than each getting a two-def file of their own: they form two
  independent same-file chains that each cap at Layer 2, and layer
  count is per-def-longest-chain, not additive across unconnected
  islands, so combining them doesn't cost anything against the budget.
  Splitting them further seemed like fragmentation for its own sake
  given neither forces a cut on its own.
- **`diagnostic_plan.clj`** (3 layers) — `parse-diagnostic-args` (CLI
  arg parsing) and `diagnostic-test-plan` (plan assembly), plus their
  private `parse-long-arg`/`assoc-long-arg` helpers. Requires
  `stable-derived` for `parse-error`, `parse-project-selector`,
  `format-project-selector`, `order-projects`, `expand-project-groups`,
  and `bisect-project-groups`.
- **`deps_config.clj`** (2 layers) — `normalized-alias-keys`,
  `merge-deps-config`. Kept separate from `coverage-cmd` (its only
  caller) specifically because folding it back in re-creates the
  over-budget chain described above.
- **`coverage_cmd.clj`** (3 layers) — pure Cloverage argv derivation:
  `cloverage-version`, `default-coverage-output`,
  `coverage-install-args`, `build-coverage-sdeps`, `coverage-args`.
  Requires `deps-config` and `coverage-paths`.
- **`coverage_exec.clj`** (2 layers) — the I/O boundary:
  `load-deps-config`, `install-coverage-tool`, `run-coverage`. Requires
  `coverage-cmd`. No dedicated test file — none of these three were
  under direct `clojure.test` coverage before the split either (same
  status quo as `run-all`: exercised through the `bb.edn` task, not
  `cognitect.test-runner`).
- **`interface.clj`** (1 layer, unchanged shape) — still the sole
  public-facing namespace; only its `:require` and each function body's
  call target changed, from `core/f` to `<new-ns>/f`. Confirmed this is
  safe: grepped the whole repo for any caller reaching past `interface`
  into `core` directly — none exists. The only external caller,
  `components/bb-dev-tools/src/ai/miniforge/bb_dev_tools/adapters/cloverage.clj`,
  goes exclusively through `ai.miniforge.bb-test-runner.interface`
  (`coverage-install-args`, `load-deps-config`, `coverage-args`) and
  needed no changes.

One duplication caught before it landed: an early draft of
`diagnostic-plan.clj` reimplemented `parse-error` locally instead of
requiring it from `stable-derived`, since both `parse-project-list-
output` (stable-derived) and `parse-long-arg` (diagnostic-plan) build
the same `{:ok? false :error {...}}` shape. Fixed to keep one canonical
`parse-error` in `stable-derived` (now public, `defn` instead of
`defn-`) and have `diagnostic-plan` call it cross-namespace.

The test file `core_test.clj` (32 deftests across the whole component)
is deleted and split into one test file per new source namespace
(matching this repo's established convention — e.g. `components/llm`'s
`cost.clj`/`cost_test.clj`), each requiring only its own namespace as
`sut`: `test_discovery_test.clj` (6), `coverage_paths_test.clj` (1),
`deps_config_test.clj` (2), `coverage_cmd_test.clj` (2),
`stable_derived_test.clj` (18), `diagnostic_plan_test.clj` (3). No test
bodies changed — only namespace declarations, requires, and which file
each `deftest` lives in.

## Testing Plan

1. Confirmed the branch base: rebased onto current `main`, verified
   `git log main -- components/bb-test-runner` shows #1526 as the tip
   before branching, and re-read `tasks/stratum.clj` for the current
   pin SHA (`bef8657a2efd3b1ba9e1a4f510693c9fbca45abd`) rather than
   reusing an old one.
2. Ran plain (non-`--fix`) `stratum-lint` on `core.cljc` before any
   change — reproduced the exact deferred `SL003` finding (5 layers).
3. Moved every def by hand into its new namespace (no `--fix` for the
   split itself — `--fix` reorders within a file, it cannot move code
   across files).
4. Loaded every new namespace individually under both runtimes after
   the split: `clojure -Sdeps ... -M -e "(require '<ns>)"` for each of
   the 8 namespaces, and the Babashka equivalent
   (`bb -cp components/bb-test-runner/src -e "(require '<ns>)")`) —
   all succeed under both. Also re-confirmed the JVM-side unsupported-
   runtime guard: calling `run-all` under JVM Clojure throws the same
   `ex-info` as before (message and `:runtime :jvm` unchanged; only the
   reported `:namespace` value changed to reflect the new namespace
   name, which is correct).
5. Ran plain `stratum-lint` per new file and over the whole component
   (`components/bb-test-runner`): zero findings, all 7 new files ≤ 3
   layers, `interface.clj` still 1 layer.
6. Ran `--fix` over the whole component: only `interface.clj` changed
   (gained `^{:stratum n}` metadata — expected, since it hadn't been
   through a stratifying `--fix` pass with this shape before). The 7
   split source files and all 6 new test files were already exact
   fixed points on the first `--fix` pass — confirms the by-hand layer
   assignments matched the tool's own inference.
7. Ran `--fix` a second time (whole component): zero further changes —
   confirmed via `git status` showing no new modifications and a
   file-hash comparison on `interface.clj`/`test_discovery.cljc` before
   and after. Stable fixed point.
8. `clj-kondo --lint components/bb-test-runner`: 0 errors, 0 warnings
   (one `Unresolved namespace System` warning surfaced during
   iteration, from `test_discovery.cljc`'s `run-all` using
   `System/exit` without the explicit `:import` the original file
   carried for all java.lang classes it touched — fixed by adding
   `(:import [java.lang System])`, matching this codebase's existing
   convention of explicitly importing java.lang classes even though
   the JVM/Babashka reader would resolve them either way).
9. `clj-kondo --lint components/bb-dev-tools`: 0 errors, 0 warnings —
   confirms the one real external caller still resolves cleanly against
   the updated `interface.clj`.
10. Ran the component's test suite after the split:
    `clojure -Sdeps '{:paths ["components/bb-test-runner/src"
    "components/bb-test-runner/test"] :deps {babashka/fs {:mvn/version
    "0.5.22"} io.github.cognitect-labs/test-runner {:git/tag "v0.5.1"
    :git/sha "dfb30dd"}}}' -M -m cognitect.test-runner -d
    components/bb-test-runner/test` → 32 tests, 64 assertions, 0
    failures/errors — exact match with the pre-split baseline recorded
    in #1526's own PR doc.
11. No `--no-verify`; pre-commit's `lint:stratum` hook ran normally at
    commit time.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change: every moved function's body is byte-for-byte identical to its
pre-split form (aside from qualifying formerly-same-file calls with the
new namespace alias, e.g. `merge-deps-config` → `deps-config/merge-
deps-config`). The one external caller
(`components/bb-dev-tools/.../adapters/cloverage.clj`) is unaffected —
it only ever went through `interface.clj`, which keeps its full public
API surface unchanged. `SL003` is now fully resolved for this
component, not deferred — no `MINIFORGE_STRATUM_BUDGET_MODE=warn`
needed at commit time.

## Related Issues/PRs

- Deferred by: miniforge [#1526](https://github.com/miniforge-ai/miniforge/pull/1526)
  (`fix: restructure bb-test-runner's reader-conditional to clear
  SL008`) — its "Deployment Plan" section names this component's "own
  Wave 1 namespace-split PR" as open work; this PR closes that.
- Same deferred-then-resolved-in-a-follow-up shape as
  `components/artifact`'s `transit_store.clj` (#1514, deferred) and
  `components/repo-dag`'s `core.clj`/`schema.clj` (#1524, deferred to a
  still-open Wave 2).
- Baseline/wave tracking: `work/stratum-lint-baseline-2026-07-24.md`
  (Wave 2 — real namespace splits).
- SL008 guard origin: [stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15);
  pin bump miniforge [#1515](https://github.com/miniforge-ai/miniforge/pull/1515).

## Checklist

- [x] Rebased onto current `main`; confirmed #1526 (SL008 fix) is
      already merged before branching
- [x] Confirmed the current `stratum-lint` pin from `tasks/stratum.clj`
      rather than reusing an old SHA
- [x] Plain lint reproduced the exact deferred `SL003` finding (5
      layers) before any change
- [x] Namespace split done by hand, not via `--fix`
- [x] Corrected one stale grouping assumption: `coverage-paths`'
      helpers were mislabeled as test-discovery helpers pre-split;
      verified against the actual same-file reference graph and placed
      with their real (sole) caller, `classify-coverage-paths`
- [x] Caught and fixed one accidental duplication (`parse-error`)
      before landing it — kept a single canonical definition
- [x] Grepped the whole repo for callers of the old `core` namespace
      and of every moved symbol; only external caller
      (`bb-dev-tools/adapters/cloverage.clj`) goes through `interface`,
      unchanged
- [x] `run-all`/`classpath-test-roots` Babashka pairing kept together;
      reader-conditional stays inside the function body (`:default`),
      not wrapping a top-level `defn` (the SL008 shape)
- [x] Every new namespace loads under both `bb` and JVM Clojure
      directly (not just via the test runner)
- [x] JVM-side unsupported-runtime `ex-info` still fires with the same
      message/`:runtime` after the split
- [x] All 7 new source files + `interface.clj` confirmed ≤ 3 layers via
      plain `stratum-lint`, individually and over the whole component
- [x] `--fix` is a no-op on every file except `interface.clj`
      (metadata-only); confirmed idempotent on a second `--fix` pass
- [x] `clj-kondo` clean on `components/bb-test-runner` and on
      `components/bb-dev-tools` (the external caller)
- [x] Test file split one-for-one with the new source namespaces,
      matching this repo's established multi-namespace-component
      convention; no test bodies changed
- [x] Component tests pass: 32 tests, 64 assertions, 0 failures/errors
      — unchanged from the pre-split baseline
- [x] No `--no-verify`; pre-commit hook ran normally at commit time
- [x] `SL003` fully resolved — no `MINIFORGE_STRATUM_BUDGET_MODE=warn`
      needed
