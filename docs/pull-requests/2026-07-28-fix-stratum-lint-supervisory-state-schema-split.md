<!--
  Title: Split supervisory-state's schema.clj to clear SL003 (Wave 2)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(supervisory-state): split schema.clj to clear the 3-layer stratum budget (SL003, Wave 2)

## Overview

`components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj`
(583 lines) reported `stratum-lint`'s `SL003`: 5 distinct layers against
the 3-layer budget (rule 210). Unlike the Wave 1 `--fix`-only PRs, this is
a genuine over-budget file, not a decorative-heading mislabel — the same-
file reference graph really is 5 deep. Split by hand into two namespaces,
each landing at ≤ 3 real layers:

- `schema.clj` (kept name) — contract version, enums, the Malli `registry`,
  the four standalone PR-scoring sub-schemas (`ReadinessFactor`,
  `RiskFactor`, `PolicyViolationSummary`, `PolicyCounts`), and the
  `empty-table` seed. 2 real layers (enums/sub-schemas → `registry`).
- `entities.clj` (new) — the ten canonical supervisory entity schemas plus
  the aggregate `EntityTable`. 3 real layers (entities that only compose
  `schema/registry` → `PrFleetEntry`/`PolicyEvaluation`, which compose
  sibling entities in this file → `EntityTable`, which composes both).

## Motivation

Confirmed the baseline with a fresh plain-lint run before touching
anything:

```text
components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj:568:1: SL003 file uses 5 distinct layers (max 3); split the namespace or extract a component
```

Reconstructed the file's actual same-file reference graph (not the
existing — partially decorative — headings) by tracing which `def`s
appear as bare symbols inside another `def`'s body:

- **Layer 0** (26 defs): every enum vector, the four PR-scoring
  sub-schemas (`ReadinessFactor`, `RiskFactor`, `PolicyViolationSummary`,
  `PolicyCounts` — none reference `registry` or each other), and
  `empty-table` (a plain literal map).
- **Layer 1**: `registry`, which calls `(into [:enum] <layer-0-enum>)`
  for ~20 of the Layer 0 enums — a real same-file dependency.
- **Layer 2**: `WorkflowRun`, `Spec`, `AgentSession`, `PrReadiness`,
  `PrRisk`, `PrPolicy`, `PolicyViolation`, `AttentionItem`, `TaskNode`,
  `DecisionCard`, `InterventionRequest`, `DependencyHealth` — all consume
  `registry` via `{:registry registry}`, and `PrReadiness`/`PrRisk`/
  `PrPolicy` additionally reference the Layer 0 sub-schemas.
- **Layer 3**: `PrFleetEntry` (composes `PrReadiness`/`PrRisk`/`PrPolicy`)
  and `PolicyEvaluation` (composes `PolicyViolation`) — both same-file
  references to Layer 2 defs.
- **Layer 4**: `EntityTable`, which composes entities from both Layer 2
  and Layer 3.

Five real layers, matching the lint finding exactly — this ruled out a
mechanical `--fix` (which only re-labels headings; it does not move code
across files) and confirmed a genuine split was needed, per the file's
own `SL003` message ("split the namespace or extract a component") and
this repo's `languages/clojure` (210) namespace-splitting strategy.

The split follows 210's guidance for `schema.clj`/`spec.clj`-shaped files
directly: "if it's over budget it's likely because schemas reference/
compose other schemas, creating layered dependency. Split by schema
domain/grouping." The natural domain cut here is *vocabulary* (enums,
registry, the four leaf sub-schemas that don't need the registry) versus
*entities* (the ten canonical open-map entities the registry exists to
serve, plus their two composites and the aggregate table) — not a finer
per-entity split, since every entity in the "entities" group only
depends on `registry` (an external reference once moved) and therefore
lands at the same real layer as its siblings; only the two composites
(`PrFleetEntry`, `PolicyEvaluation`) and the aggregate (`EntityTable`)
add genuine additional layers.

## Changes in Detail

- **`schema.clj`** — kept the file name (per this component's convention
  that `schema.clj` is the canonical Malli-schemas namespace) and kept
  every symbol that stays a pure Layer 0/1 vocabulary def: `schema-
  version`, all 21 enum vectors, `ReadinessFactor`, `RiskFactor`,
  `PolicyViolationSummary`, `PolicyCounts`, `empty-table` (moved up next
  to the other Layer 0 defs — it was previously sandwiched after the
  `Layer 1` heading behind a stale, decorative `;---- Layer 0a` sub-
  banner left over from an earlier ad hoc heading scheme; that stray
  banner is deleted, matching the same tool-limitation class the Wave 1
  `components/schema` PR hit and hand-fixed), and `registry`. Updated the
  namespace docstring and a few in-line comments to point at
  `ai.miniforge.supervisory-state.entities` for the symbols that moved,
  instead of describing them in place. No `^{:stratum n}` metadata values
  changed on anything that stayed (`empty-table` was already correctly
  tagged `0`; only its physical position moved).
- **`entities.clj`** (new) — the ten entity schemas, `PrFleetEntry`,
  `PolicyEvaluation`, and `EntityTable`, moved verbatim (docstrings,
  comments, key ordering unchanged) with two mechanical edits: every
  `{:registry registry}` became `{:registry schema/registry}`, and the
  four references to the sub-schemas that stayed behind became
  `schema/ReadinessFactor`, `schema/RiskFactor`, `schema/PolicyCounts`,
  `schema/PolicyViolationSummary`. Re-derived `^{:stratum n}` metadata
  and headings for the new, shallower real depth (12 entities at Layer 0,
  `PrFleetEntry`/`PolicyEvaluation` at Layer 1, `EntityTable` at Layer 2)
  — confirmed byte-identical to the pre-split defs otherwise via a
  normalized diff (substituting the qualified refs back to bare symbols
  and comparing against the deleted block: zero content diff, only
  heading/stratum-number lines differ).
- **`interface.clj`** — added `[ai.miniforge.supervisory-state.entities
  :as entities]` and repointed the eight re-exported entity schemas
  (`Spec`, `WorkflowRun`, `AgentSession`, `PrFleetEntry`,
  `PolicyEvaluation`, `PolicyViolation`, `AttentionItem`,
  `InterventionRequest`) from `schema/X` to `entities/X`. `schema-
  version` and `empty-table` re-exports are untouched — those symbols
  did not move. By hand, this PR did not otherwise touch the file's
  pre-existing `SL002` finding (a duplicate `Layer 0` heading, present
  before this change and unrelated to the schema split) — but staging
  the file triggered the repo's standard pre-commit `lint:stratum`
  autofix, which resolved it as a mechanical side effect (headings +
  `^{:stratum n}` metadata only — see Testing Plan item 9 and Deployment
  Plan).
- **`golden_fixtures.clj`** — added the `entities` require and repointed
  the nine `:schema` values in the `families` table (used to validate
  each golden fixture entity before serializing it) from `schema/X` to
  `entities/X`. The two `schema/schema-version` uses are untouched.
- **`golden_fixtures_test.clj`** — same-component test file that referred
  to entity schemas directly (not through `.interface`, which is allowed
  for same-component tests per rule 210's Polylith boundary — only
  *cross-component* deps must go through `.interface`); added the
  `entities` require and repointed `family->schema` and the one direct
  `schema/WorkflowRun` use in `validate-result-returns-anomaly-for-
  invalid-entity`. `schema/schema-version` is untouched.
- Grepped the whole repo for `ai.miniforge.supervisory-state.schema`
  before and after: no cross-component caller reaches past `.interface`
  into `.schema` directly (`deps.edn` for every other component depends
  on `ai.miniforge/supervisory-state`, i.e. the interface artifact, never
  the raw source tree), and after the edit the only remaining
  `schema/PolicyViolationSummary`-shaped bare reference is the intended
  one inside `entities.clj` itself.
- Did **not** touch `core.clj`, `attention.clj`, `emitter.clj`, or any
  other sibling file beyond the two requires/re-export edits above — the
  component's other pre-existing `SL003`/`SL004`/`SL002` findings
  (`attention.clj` 7 layers, `core.clj` 4 layers, `emitter.clj` def-
  before-heading, `interface.clj`'s duplicate-heading `SL002`) are
  unrelated to `schema.clj` and out of scope for this PR (see Testing
  Plan and Deployment Plan).

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` on `components/supervisory-
   state` before any change — reproduced the exact `schema.clj` `SL003`
   finding (5 layers) and also surfaced, unprompted, five *pre-existing*
   findings in sibling files that this PR does not touch:
   `attention.clj` (`SL003`, 7 layers), `core.clj` (`SL003`, 4 layers),
   `emitter.clj` (`SL004`), `golden_fixtures.clj` (`SL003`, 6 layers),
   `interface.clj` (`SL002`, duplicate `Layer 0` heading). None of these
   mention `schema.clj` and all five reproduce identically after this
   PR's change — confirmed by diffing the before/after lint output byte
   for byte on those five lines.
2. Applied the split, then ran plain `stratum-lint` again over the whole
   component: `schema.clj`'s `SL003` finding is gone; `entities.clj`
   (new) reports nothing. The five pre-existing findings above remain
   unchanged — **the component as a whole is not lint-clean after this
   PR**; only the finding this PR targets is resolved. Flagged explicitly
   because the task brief anticipated a fully clean component-wide run
   modulo one documented `core.clj` `SL001` false positive, and the
   live state instead has several other genuinely over-budget/decorative
   files that are each their own Wave 2 (or Wave 1 `SL002`/`SL004`) unit
   of work.
3. Ran the exact two commands to isolate the claim precisely:
   - `bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e"
     :deps/root "clojure"}}}' -m stratum-lint.interface components/supervisory-state` → still exits 1 (the five
     pre-existing, out-of-scope findings above).
   - Same command scoped to just the two changed/new files
     (`schema.clj entities.clj`) → exit 0, no output.
   - Repeated both with this repo's currently-pinned `stratum-lint` SHA
     from `tasks/stratum.clj` (`bef8657a2efd3b1ba9e1a4f510693c9fbca45abd`,
     different from the SHA named in the task brief) — same result: the
     two changed/new files are clean under both pins.
4. Normalized-diff check: substituted `schema/registry` → `registry` and
   the four qualified sub-schema refs back to bare symbols in
   `entities.clj`, stripped the new namespace's header/ns form, and
   diffed against the deleted block of the old `schema.clj`. Zero
   content differences — only heading text and `^{:stratum n}` numbers
   differ (2/3/4 → 0/1/2, as expected for a file that now starts its own
   count from zero).
5. `clj-kondo --lint components/supervisory-state`: 0 errors, 0 warnings.
6. Component tests: `clojure -M:test -m cognitect.test-runner` (run from
   `components/supervisory-state`) → 87 tests, 279 assertions, 0
   failures, 0 errors (`accumulator-test`, `attention-test`, `core-test`,
   `golden-fixtures-test`).
7. `bb poly:check` (Polylith workspace structure check) — passes; the
   two pre-existing warnings it reports (`config` non-top namespace,
   `data-foundry` unnecessary component) are unrelated to this change
   and unaffected by it.
8. `bb test` (monorepo-wide stable-derived changed-and-affected suite):
   completed with exit 0, no failures/errors reported anywhere in its
   output. Took well over 10 minutes of wall clock — the shared dev
   machine had five other `bb scripts/test-since-stable.bb` processes
   running concurrently from sibling Wave 2 PRs' agents at the same
   time, plus this run's own `dag-executor` suite waiting out several
   120s Docker-unavailable timeouts (pre-existing behavior, unrelated to
   this change — those tests SKIP when Docker isn't present). The
   captured tail of the run's output (last ~200 lines) happened to land
   inside `dag-executor`/`control-plane` output and doesn't show
   `supervisory-state`'s own namespaces by name — that run scrolled by
   earlier in the log — so this is corroborating, not the primary
   evidence for this component specifically; item 6 (the component's own
   test run) is the direct evidence for `supervisory-state` and its
   golden-fixtures contract.
9. Attempting to commit surfaced two things worth recording:
   - The commit-budget pre-commit gate reported 658 reportable lines
     (schema.clj's ~all-touched 276, entities.clj's new 267, plus the
     three requires-only files) against the 200-line default — expected
     for a namespace split where the "diff" is mostly relocated content,
     not new logic. Committed with `MINIFORGE_COMMIT_BUDGET_OVERRIDE`
     carrying that rationale, per this repo's documented escape hatch
     for exactly this shape of change (see e.g.
     `docs/pull-requests/2026-05-06-refactor-agent-component-cleanup.md`
     and several Wave 1 stratum-lint PRs).
   - The pre-commit `lint:stratum` autofix step re-ran `--fix` over all
     five staged files as part of the gate itself. It left `entities.clj`
     byte-identical to what was authored by hand (confirms the manual
     layer assignment matches the tool's own inference) and made a
     mechanical, headings/`^{:stratum n}`-metadata-only change to
     `interface.clj`: collapsing its pre-existing decorative duplicate
     `Layer 0` heading (the `SL002` finding noted above as "out of
     scope") into a single real `Layer 0`, since every def there is an
     independent pass-through with no same-file references. This is a
     welcome side effect of the standard gate, not a change this PR set
     out to make by hand — see Changes in Detail and Deployment Plan.
     `golden_fixtures.clj` still reports its own pre-existing `SL003` (6
     layers) after autofix, unrelated to this split; committed with
     `MINIFORGE_STRATUM_BUDGET_MODE=warn`, the documented convention for
     touching a file with a pre-existing over-budget finding this PR
     does not resolve (see e.g.
     `docs/pull-requests/2026-07-26-fix-stratum-lint-wave1-workflow-resume.md`).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change: every moved schema value is byte-for-byte identical to its
pre-split form (aside from qualifying formerly-same-file symbol refs with
the new `schema/` alias, and `interface.clj`'s mechanical heading/
metadata-only autofix). The public interface (`ai.miniforge.supervisory-
state.interface`) exposes exactly the same symbols under exactly the same
names as before — only their internal `def` target changed from
`schema/X` to `entities/X`. `schema.clj`'s `SL003` is fully resolved, not
deferred — no `MINIFORGE_STRATUM_BUDGET_MODE=warn` needed for this
finding, and `interface.clj`'s `SL002` is now also resolved (side effect
of the pre-commit autofix, not by-hand scope). The component's remaining
pre-existing findings (`attention.clj` `SL003` 7 layers, `core.clj`
`SL003` 4 layers — plus the documented `SL001` false positive at
`core.clj:52`, `emitter.clj` `SL004`, `golden_fixtures.clj` `SL003` 6
layers) remain open as separate Wave 2 (or Wave 1) units of work, tracked
below; this commit carries `MINIFORGE_STRATUM_BUDGET_MODE=warn` only
because it had to touch `golden_fixtures.clj`'s requires, not because it
introduces any new over-budget file.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 2 — real
  namespace splits).
- Same deferred-then-split shape as
  `docs/pull-requests/2026-07-26-fix-stratum-lint-bb-test-runner-namespace-split.md`.
- Sibling `components/schema` (a *different*, shared component —
  `ai.miniforge.schema.*`, consumed here as `shared/severities`) hit the
  identical stray-`Layer 0a`-banner tool-limitation class in its own
  Wave 1 PR: `docs/pull-requests/2026-07-26-fix-stratum-lint-wave1-schema.md`.
- Still open in `components/supervisory-state`, out of scope here:
  `attention.clj` (`SL003`, 7 layers), `core.clj` (`SL003`, 4 layers —
  also carries the documented `SL001` false positive at `core.clj:52`,
  a `table` parameter shadowing the later `defn table`, per
  `work/stratum-lint-baseline-2026-07-24.md`), `emitter.clj` (`SL004`),
  `golden_fixtures.clj` (`SL003`, 6 layers). `interface.clj`'s `SL002`
  (duplicate `Layer 0` heading) was resolved as a side effect of this
  PR's pre-commit autofix — see Testing Plan item 9.

## Checklist

- [x] Plain lint reproduced the exact `schema.clj` `SL003` finding (5
      layers) before any change, and separately captured the five
      pre-existing findings on sibling files this PR does not touch
- [x] Namespace split done by hand (data-only defs; `--fix` cannot move
      code across files)
- [x] `schema.clj` kept as the vocabulary namespace (enums, registry,
      leaf sub-schemas, `empty-table`); stray decorative `;---- Layer 0a`
      banner removed
- [x] New `entities.clj` holds the ten entities + `PrFleetEntry` +
      `PolicyEvaluation` + `EntityTable`, confirmed content-identical to
      the deleted block via a normalized diff
- [x] `interface.clj`, `golden_fixtures.clj`, `golden_fixtures_test.clj`
      repointed for the symbols that moved; `schema-version`/
      `empty-table` re-exports left untouched (they didn't move)
- [x] Pre-commit `lint:stratum` autofix left `entities.clj` byte-
      identical to the hand-authored version (confirms the by-hand layer
      assignment matches the tool's own inference) and mechanically
      resolved `interface.clj`'s pre-existing `SL002` as a side effect
- [x] Grepped the whole repo for `ai.miniforge.supervisory-state.schema`
      and for each moved entity schema symbol; no cross-component caller
      reaches past `.interface`
- [x] `core.clj`, `attention.clj`, `emitter.clj` untouched (verified via
      `git status`/`git diff --stat`)
- [x] Both new/changed files confirmed 0 findings via plain `stratum-
      lint`, scoped individually and matching the task-specified pin and
      this repo's currently-pinned SHA in `tasks/stratum.clj`
- [x] Whole-component lint run is **not** clean after this PR — five
      pre-existing, out-of-scope findings remain and are called out
      explicitly rather than glossed over
- [x] `clj-kondo` clean on `components/supervisory-state` (0/0)
- [x] Component tests pass: 87 tests, 279 assertions, 0 failures/errors
- [x] `bb poly:check` passes (pre-existing, unrelated warnings only)
- [x] `bb test` (monorepo-wide, stable-derived changed-and-affected)
      completed with exit 0; no failures/errors anywhere in its output
      (see Testing Plan item 8 for the caveat on what its captured tail
      does/doesn't show by name)
- [x] Committed with `MINIFORGE_COMMIT_BUDGET_OVERRIDE` (namespace-split
      shape, rationale in the commit) and `MINIFORGE_STRATUM_BUDGET_MODE=
      warn` (pre-existing `golden_fixtures.clj` `SL003`, unrelated to
      this split) — both documented, established escape hatches, not a
      hook bypass
- [x] No `--no-verify`; pre-commit hook ran normally (including its own
      lint/format/smoke-test steps) at commit time
