<!--
  Title: Split policy-pack/loader.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split loader.clj (rule 210)

## Overview

Splits disk-I/O, pack/rule normalization, and timestamp wire-form
conversion out of `ai.miniforge.policy-pack.loader` into three new
sibling namespaces, resolving a stratum-lint SL003 finding (the
combined namespace measured 5 real layers, over the rule 210 budget
of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2,
policy-pack batch 2. `loader.clj` was 724 lines and the largest
outstanding file in the component (confirmed via `stratum-lint`:
`SL003 file uses 5 distinct layers (max 3)`).

## Changes in Detail

- New file `loader/timestamps.clj`: `map-instant-keys`, `->iso`,
  `ensure-instant` — the ISO-8601 <-> `Instant`/`Date` wire-form
  conversions at the disk boundary. 1 real layer.
- New file `loader/normalize.clj`: `normalize-rule`, `normalize-pack`
  — required-field defaults and collection-type coercion for rules and
  packs. 2 real layers.
- New file `loader/io.clj`: `find-rule-files`, `pack-file?`,
  `safe-read-edn`, `write-pack-to-file`, `load-rule-file`,
  `discover-packs` — EDN parsing/writing and directory scanning. 2 real
  layers.
- `loader.clj`: the 11 functions above are deleted; `load-pack-from-file`,
  `load-pack-from-directory`, and `load-all-packs` now call the moved
  code via `loader-io/*` and `normalize/*`. What's left — overlay
  resolution (N4 §2.5), the dependency-validation wrapper, trust
  validation (N1 §2.10.2), and load-path orchestration — was not itself
  split further: extracting the file-IO/normalization layer also
  shortened this namespace's own in-file call chain (those hops no
  longer count toward local layer depth), so the remainder now measures
  3 real layers on its own (`stratum-lint` exit 0, confirmed directly,
  not assumed). The ns docstring's layer summary is updated to match.
  418 lines, down from 724.
- `interface/loading.clj`: `discover-packs` and `write-pack-to-file` now
  redirect to `loader.io` (they moved there); `load-pack`,
  `load-pack-from-file`, `load-pack-from-directory`, `resolve-overlay`,
  and `load-all-packs` are unchanged, still forwarding to
  `ai.miniforge.policy-pack.loader` (`resolve-overlay` did not move —
  it was already within budget and not part of this split).
- `loader_test.clj`: the `discover-packs` call updated to the
  `loader-io` alias.
- `loader_timestamps_test.clj`: `write-pack-to-file` calls updated to
  the `loader-io` alias; `ensure-instant` calls (a white-box test of the
  boundary's refusal behavior) updated to the `loader.timestamps` alias.
- `overlay_test.clj`: untouched — `resolve-overlay` stayed in
  `loader.clj`.

This is pure code motion aside from the test call-site updates above —
no detection/validation/normalization logic changed.

## Testing Plan

- Repo-wide grep on the fully-qualified namespace
  (`ai\.miniforge\.policy-pack\.loader\b`, across
  `components`/`bases`/`projects`, not a symbol-prefix guess — the
  methodology two earlier splits in this program got bitten by)
  confirmed exactly four callers before starting: `loader_test.clj`,
  `overlay_test.clj`, `loader_timestamps_test.clj`, and
  `interface/loading.clj`. No caller under `projects/miniforge/test/`.
- `stratum-lint` clean (exit 0) on all four touched/new source files;
  `loader.clj` specifically confirmed clean after each of the two
  commits that changed it, not just at the end.
- `clj-kondo` clean on every touched/new file (0 errors, 0 warnings).
- `clojure -M:test`: `ai.miniforge.policy-pack.loader-test`,
  `overlay-test`, and `loader-timestamps-test` — 22 tests, 74
  assertions, 0 failures. `ai.miniforge.policy-pack.interface` compiles
  clean (full component classpath).
- Pre-commit's smoke suite (`bb pre-commit`, cross-component) ran clean
  on every commit: 345 tests / 1301 assertions, plus 8 GraalVM
  compatibility tests / 623 assertions, 0 failures throughout.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Precedent: `mdc_compiler.clj` split, miniforge#1729-#1734 (6-PR train
  — the closest precedent for a large file, including how it handled a
  fan-in it initially missed mid-train).
- Precedent: `workflow_runner.clj` split, miniforge#1662-#1667 (the
  original convention).
- Precedent: `knowledge_safety.clj` split (2026-08-09, same component)
  — the fan-in-by-alias miss this PR's grep methodology was written to
  avoid.
- Part of the stratum-lint rule-210 remediation program (Wave 2, batch 2).

## Checklist

- [x] Zero unaccounted-for fan-in confirmed via fully-qualified
      namespace grep before starting
- [x] Pure code motion — no logic/behavior changes
- [x] `stratum-lint` clean on every touched/new file
- [x] `clj-kondo` clean
- [x] Tests green (22/22, 74 assertions) + full-component compile check
- [x] Pre-commit smoke suite green on every commit (not just the last)
- [x] PR-diff and commit-diff budgets checked (432 insertions / 324
      deletions across 7 files total; each of the 3 commits ≤200
      reportable lines)
- [x] Adversarial self-review: diffed `loader.clj` end to end against
      the original — every relocated def is byte-identical apart from
      its `:stratum` tag/heading and call-site qualification; no
      def added, removed, or behaviorally altered
