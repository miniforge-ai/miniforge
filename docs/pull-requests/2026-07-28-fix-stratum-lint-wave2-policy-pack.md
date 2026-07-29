<!--
  Title: Split core.clj and schema.clj to clear the stratum budget (policy-pack, Wave 2)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: split core.clj and schema.clj to clear the 3-layer stratum budget (policy-pack, SL003, Wave 2)

## Overview

Splits the two `components/policy-pack` files Wave 1 left over budget —
`core.clj` (7 real layers) and `schema.clj` (6 real layers) — into five new
namespaces along their real same-file reference graphs, per rule 210
(`standards/miniforge/languages/clojure.mdc` §"Namespace splitting
strategy"). Every new file lands at ≤ 3 real layers. No behavior change:
every `def`/`defn` moved with its docstring and body intact — only the
symbols that now live in a different namespace got re-qualified.

## Motivation

Wave 1 (`docs/pull-requests/2026-07-26-fix-stratum-lint-wave1-policy-pack.md`)
fixed decorative mislabeling across the whole component and left 18
genuine `SL003` findings for Wave 2 — real files whose same-file reference
graph is deeper than 3 layers, not a labeling problem. This PR clears two
of those 18: `core.clj` and `schema.clj`, the two named in this task. The
other 16 (`builtin_detectors.clj`, `compiler.clj`, `detection.clj`,
`external.clj`, `intent.clj`, `knowledge_safety.clj`, `loader.clj`,
`mapping.clj`, `mdc_compiler.clj`, `prompt_template.clj`, `registry.clj`,
`repair.clj`, `taxonomy.clj`, `rules/pack_dependency_validation.clj`, and
two test files) are unrelated Wave 2 work, out of scope here, and are
untouched — the stratum-lint run below confirms their finding counts are
unchanged before and after this PR.

## Changes in Detail

### `core.clj` (7 layers → 4 files, each ≤ 3 layers)

The original file's real reference chain was: severity/enforcement
comparison → rule-applicability predicates → pack/rule construction →
rule merge/resolve → artifact checking. Split along that chain into four
coherent domains:

- **`enforcement.clj`** (new, 3 layers) — severity/enforcement comparison:
  `enforcement-order`/`compare-severity`/`more-severe` (pass-throughs to
  the shared vocabulary, Layer 0), `compare-enforcement` (Layer 1, over
  `enforcement-order`), `stricter-enforcement` (Layer 2, over
  `compare-enforcement`).
- **`applicability.clj`** (new, 2 layers) — does a rule apply to this
  artifact/task/phase: `rule-applies-to-artifact?`/`-task?`/`-phase?`
  (Layer 0, independent predicates), `filter-applicable-rules` (Layer 1,
  over all three).
- **`builders.clj`** (new, 2 layers) — pack/rule construction and
  multi-pack resolution: `create-pack`, `add-rule-to-pack`,
  `remove-rule-from-pack`, `update-pack-categories`, `create-rule`, the
  three detection-config constructors, the three enforcement-config
  constructors, and `merge-rules` (Layer 0 — `merge-rules` calls
  `enforcement/more-severe` and `enforcement/stricter-enforcement`, both
  now *external*, so it has no same-file dependents and lands at Layer 0,
  not Layer 1 as in the original monolith); `resolve-rules` (Layer 1, over
  `merge-rules`).
- **`core.clj`** (trimmed, 2 layers) — the two above wired into artifact
  checking: `ordered-validation` and `check-artifact` (Layer 0 — both
  orchestrate only *external* namespaces, `builders/resolve-rules`,
  `applicability/filter-applicable-rules`, `detection/*`, so neither has a
  same-file dependent); `check-artifacts` (Layer 1, over `check-artifact`).

One naming note: `builders.clj` aliases `enforcement.clj` as `enf`, not
`enforcement` — `create-rule`'s `enforcement` parameter (the
enforcement-config argument, keeping its original core.clj name) would
otherwise shadow the alias inside that function's scope. Purely a local
name choice; `create-rule`'s call signature is unchanged.

### `schema.clj` (6 layers → 3 files, each ≤ 3 layers)

The original file's real composition chain was: base enums → component
schemas (RuleApplicability/RuleDetection/RuleEnforcementConfig/etc.) →
Rule → PackManifest → their `valid-*?`/`validate-*` wrappers, plus a
domain-independent block of generic Malli/result helpers
(`valid?`/`validate`/`explain`/`succeeded?`/`success`/`failure`/
`failure-with-errors`) that never referenced any of policy-pack's own
schemas at all. Split into three files:

- **`schema-types.clj`** (new, 3 layers) — every base enum and component
  schema Rule is built from: severities/enforcement-actions/detection-
  types/task-types/repo-types/approver-types, `DetectionMode`,
  `RemediationStrategy`/`Type`, `ExcludeContext`, `RuleExample`,
  `PackCategory`, `PackDependency`, `TrustLevel`, `AuthorityChannel`,
  `TaxonomyRef` (Layer 0); `RuleEnforcement`, `DetectionType`, `TaskType`,
  `RepoType`, `ApproverType`, `RuleRemediation`, `PackOverride` (Layer 1,
  each composes a Layer 0 enum); `RuleApplicability`, `RuleDetection`,
  `RuleEnforcementConfig` (Layer 2, compose Layer 1 schemas).
- **`schema-validation.clj`** (new, 1 layer) — the generic, domain-
  independent Malli/result helpers listed above. None reference each
  other or any policy-pack schema, so the whole file is Layer 0.
- **`schema.clj`** (trimmed, 3 layers) — `Rule` (Layer 0, composes
  `schema-types/*`, all external); `PackManifest`, `valid-rule?`,
  `validate-rule` (Layer 1, each over the same-file `Rule`);
  `valid-pack?`, `validate-pack` (Layer 2, over the same-file
  `PackManifest`).

### Same-component callers updated

Per rule 210, cross-component deps must go through `.interface`, but
files *within* policy-pack itself (its own `interface/*.clj` sub-files,
and ~5 src files + 5 test files) referenced `.core`/`.schema` directly and
needed their requires/call-sites re-pointed at wherever the code actually
landed:

- `interface/builders.clj` — `core` → `builders` (all 13 forwarded defs:
  `create-pack`, `create-rule`, `add-rule-to-pack`,
  `remove-rule-from-pack`, `update-pack-categories`, the three detection
  and three enforcement constructors, `resolve-rules`, `merge-rules`).
- `interface/checking.clj` — added `applicability`; `filter-applicable-
  rules` and `rule-applies-to-phase?` now forward from `applicability`,
  `check-artifact`/`check-artifacts` still forward from `core` (unchanged
  — they stayed there).
- `interface/schema.clj` — added `schema-types`; `RuleSeverity`,
  `RuleEnforcement`, `RuleApplicability`, `RuleDetection`,
  `rule-severities`, `enforcement-actions`, `detection-types` now forward
  from `schema-types`; `Rule`, `PackManifest`, `valid-rule?`,
  `validate-rule`, `valid-pack?`, `validate-pack` still forward from
  `schema` (unchanged — they stayed there).
- `ast.clj`, `detection.clj` — their only schema usage was
  `success`/`failure`/`succeeded?`; require now points at
  `schema-validation` (kept the local alias `schema` in both files, so
  call sites are untouched).
- `knowledge_safety.clj` — its only `core` usage was pack/rule builders
  (`create-rule`, `halt-enforcement`, `warn-enforcement`, `create-pack`,
  `add-rule-to-pack`, `update-pack-categories`); require + all 23 call
  sites moved to `builders`. Its `schema/validate-pack` usage is untouched
  (stayed in `schema`).
- `loader.clj`, `mdc_compiler.clj` — mixed usage: `validate-pack`
  (loader) / `enforcement-actions` (mdc_compiler) stayed on `schema`/
  moved to `schema-types` respectively; everything else
  (`success`/`failure`/`succeeded?`/`failure-with-errors`) moved to a new
  `schema-validation` require. `loader.clj` keeps both `schema` and
  `schema-validation` requires since it genuinely uses both.
- `registry.clj` — only used `schema/validate-pack`; no change at all.
- `external.clj` — only used `core/check-artifact`, which stayed in
  `core.clj`; no change at all.
- Tests: `external_test.clj` (`core` → `builders`), `overlay_test.clj`
  (`core` → `applicability`, plus its docstring comment), `governance_
  test.clj` (uses only `core/ordered-validation`, which stayed — no
  change), `mdc_compiler_test.clj` (its dynamic `resolve` of
  `.../schema/explain` now resolves `.../schema-validation/explain`;
  `valid-rule?`/`Rule` stayed on `schema`, unchanged), `schema_test.clj`
  (the big one — split its requires into `sut` (schema, unchanged),
  `types` (schema-types), `sv` (schema-validation), and re-pointed every
  call site to whichever namespace now owns that symbol).

No other file in the repo required `ai.miniforge.policy-pack.core` or
`.schema` directly (confirmed by a repo-wide grep before starting) — the
Polylith interface boundary (`ai.miniforge.policy-pack.interface`) was
never bypassed from outside the component, so no consumer outside
policy-pack needed any change.

## Testing Plan

1. **Form-level equality check** — wrote a babashka script that reads
   both the git `HEAD` and working-tree version of `core.clj`/`schema.clj`,
   collects every `def`/`defn`/`defn-`/`defprotocol` form from HEAD and the
   union of forms across each file's split destinations, strips all
   metadata, and compares the two as frequency-counted multisets
   (order-independent). Both files: def-count matches exactly (25=25 for
   core.clj's split, 40=40 for schema.clj's split) and the only diffed
   forms are the ones that gained a namespace-qualified reference to a
   symbol that moved elsewhere (`resolve-rules` → `builders/resolve-
   rules`, `more-severe`/`stricter-enforcement` → `enf/...`, `valid?`/
   `validate` → `schema-validation/...`, `TrustLevel`/`AuthorityChannel`/
   etc. → `schema-types/...`) — direct proof no function/schema body
   changed, only cross-references were re-qualified.
2. `clj-kondo --lint components/policy-pack`: **0 errors, 0 warnings.**
3. Ran the stratum linter (non-warn mode) before and after, diffed the
   findings:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/policy-pack
   ```

   Before: 18 `SL003` findings, including `core.clj:472` (7 layers) and
   `schema.clj:421` (6 layers). After: 16 findings — `core.clj` and
   `schema.clj` are gone from the list; the other 16 (all out of scope,
   see Motivation) are byte-for-byte the same findings at the same
   line/layer-count as before. **Zero new findings anywhere**, including
   the five new files (`enforcement.clj`, `applicability.clj`,
   `builders.clj`, `schema-types.clj`, `schema-validation.clj`), all of
   which land at their designed layer count. The overall command still
   exits 1 (16 pre-existing, unrelated findings remain in the component),
   not 0 — flagged explicitly since the task asked for a 0-exit and the
   honest answer is "0 exit only once the other 16 Wave 2 files are also
   split, which is other, unassigned work."
4. Ran all 24 `components/policy-pack` test namespaces directly via
   `clojure -M:dev:test` (from the repo root, so `standard_packs_test.clj`
   resolves against the built
   `components/phase/resources/packs/miniforge-standards.pack.edn`):
   **266 tests, 2560 assertions, 0 failures, 0 errors.**
5. Ran `bb test:poly` (`clojure -M:poly test :all` — every brick + project
   test, not just changed-bricks, per this team's own "affected-tests
   dependency gap" lesson that a store/interface change can break a
   dependent brick's tests unseen). This is a genuinely long, serial,
   whole-monorepo run; two attempts were cut short by the tool's own
   10-minute per-call ceiling (SIGTERM, not a test failure — confirmed by
   grepping the captured output for any non-zero failure/error count:
   none), and a third, detached (`nohup ... & disown`) run was still in
   progress, past the point either of the first two attempts reached,
   when this report was written. Every attempt's captured output includes
   all 24 `ai.miniforge.policy-pack.*` namespaces with 0 failures/errors,
   plus several hundred further namespaces downstream in dependency order
   (dag-executor, connector, connector-auth, config, compliance-scanner,
   anomaly, boundary, …), also 0 failures/errors throughout. Given (4) is
   the authoritative, complete signal for the component this PR actually
   touches, and (5)'s partial-but-substantial coverage shows no
   regression in anything reached so far, this is reported as the
   best-effort full-monorepo signal available inside the tool's
   per-command time budget, not as a completed guarantee — see the PR
   thread / final report for whether the detached run finished clean.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change anywhere in this diff (form-equality-verified above) — this is a
pure namespace split plus the minimal same-component require/call-site
updates it requires. No new public API surface: every symbol the
component's `interface.clj` exposed before this PR is exposed identically
after it, from the same public names, just re-pointed internally at
whichever new file now holds the implementation.

## Checklist

- [x] `core.clj` (7 real layers) split into `enforcement.clj` (3),
      `applicability.clj` (2), `builders.clj` (2), `core.clj` (2)
- [x] `schema.clj` (6 real layers) split into `schema-types.clj` (3),
      `schema-validation.clj` (1), `schema.clj` (3)
- [x] `interface/builders.clj`, `interface/checking.clj`,
      `interface/schema.clj` updated to forward from the new namespaces
- [x] All same-component direct `.core`/`.schema` references (5 src + 5
      test files) updated to point at wherever the code landed
- [x] Form-level equality check: def-count matches exactly, only diffs
      are namespace-qualification of moved symbols — zero logic change
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Stratum linter: `core.clj`/`schema.clj` findings cleared; the other
      16 pre-existing, out-of-scope findings in the component are
      unchanged (same line/layer-count before and after)
- [x] `components/policy-pack` test suite: 266 tests, 2560 assertions, 0
      failures/errors (direct, complete run)
- [x] `bb test:poly` (full monorepo): partial coverage (tool time-budget
      limited), 0 failures/errors in everything reached, including all of
      policy-pack and several hundred downstream namespaces — not
      confirmed complete; see final report for the detached run's outcome
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
