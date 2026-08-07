<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(evidence-bundle): split schema.clj to clear the 3-layer stratum budget (SL003, Wave 2)

## Overview

Splits `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj`
(477 lines, reported at 7 real layers) into five namespaces, each at or
under rule 210's 3-layer budget. Unlike the Wave 1 `--fix` PRs (mechanical
heading relabeling only), this is a genuine namespace split with real code
movement: `schema.clj`'s over-budget report was not decorative
mislabeling — it reflected an actual 7-deep same-file dependency chain.
One of the per-component Wave 2 PRs tracked from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`schema.clj` mixed four unrelated concerns in one file, each layering on
the last:

1. An `OptionalKey` marker record + its constructor/predicate
   (`optional-key`, `optional-key?`).
2. A generic `validate-schema` engine (`unwrap-key` + `validate-schema`)
   that calls into (1).
3. Compliance/retention/access-log schemas and their `valid-*?`
   predicates, which call into (2).
4. Every other N6 domain schema (intent, phase, policy, outcome,
   provenance, tool, pack-promotion, supervision, control-action) plus the
   top-level `evidence-bundle-schema` composite and its default template.

Because (3) depends on (2) which depends on (1), and the top-level
`evidence-bundle-schema` in (4) depends on (3)'s predicates, the whole
thing reduced to one 7-deep same-file reference chain: `OptionalKey` →
`optional-key?` → `unwrap-key` → `validate-schema` →
`valid-retention-policy-map?` / `valid-access-log-entry?` →
`valid-access-log?` → `evidence-bundle-schema`. Rule 210 caps a file at 3
strata; this needed an actual split, not a relabel.

## Changes in Detail

New files under `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema/`:

- **`optional_key.clj`** (`ai.miniforge.evidence-bundle.schema.optional-key`,
  2 real layers) — the `OptionalKey` record and its `optional-key`/
  `optional-key?` constructor/predicate. Nothing else in the component
  referenced these by name outside `schema.clj` itself (confirmed by
  `grep -rn` before the split), so no other file's requires needed
  updating for these two symbols.
- **`validation.clj`** (`ai.miniforge.evidence-bundle.schema.validation`,
  2 real layers) — `unwrap-key` and the generic `validate-schema` engine.
  Requires `schema.optional-key`.
- **`compliance.clj`** (`ai.miniforge.evidence-bundle.schema.compliance`,
  3 real layers) — the compliance/PII/retention/access-log domain:
  `pii-handling-types`, `data-classifications`, `regulatory-tag-values`,
  the three `default-*` constants, `retention-policy-schema`,
  `access-log-entry-schema`, `compliance-schema`, and the five `valid-*?`
  predicates (`valid-data-classification?`, `valid-regulatory-tags?`,
  `valid-retention-policy-map?`, `valid-access-log-entry?`,
  `valid-access-log?`). These five predicates were `defn-` in the
  original file; they had to become public here since `schema.clj`'s
  `evidence-bundle-schema` now references them across a namespace
  boundary. No external (non-component) consumer existed for them either
  way. Requires `schema.optional-key` + `schema.validation`.
- **`domain.clj`** (`ai.miniforge.evidence-bundle.schema.domain`,
  2 real layers) — every other N6 domain schema: intent
  (`intent-types`, `constraint-schema`, `intent-schema`,
  `semantic-validation-rules`, `semantic-validation-schema`), phase
  (`phase-result-status-values`, `phase-evidence-schema`, the three
  `*-phase-result-schema` defs), policy/violation (`policy-check-schema`,
  `violation-severities`, `violation-schema`), outcome (`pr-statuses`,
  `outcome-schema`), provenance/tool (`provenance-schema`,
  `tool-execution-schema`, `tool-invocation-schema`), pack promotion
  (`trust-levels`, `pack-promotion-schema`), supervision/control
  (`supervision-decision-schema`, `control-action-evidence-schema`), and
  `rule-applied-schema`. These are genuinely disjoint N6 sub-domains, but
  every one of them collapses to at most 1 same-file dependency hop once
  the compliance subset and the `optional-key`/`validate-schema`
  machinery are out of the file (e.g. `intent-schema` only depends on the
  same-file `intent-types`; nothing here depends on anything else here),
  so they land at 2 real layers together rather than needing further
  splitting. Requires `schema.optional-key` + the cross-component
  `ai.miniforge.schema.interface` (for `violation-severities`).

`schema.clj` itself (root, `ai.miniforge.evidence-bundle.schema`, now 1
real layer) keeps only `create-evidence-bundle-template` and
`evidence-bundle-schema` — the top-level composite and its default
template. Neither actually embeds any of the moved domain schemas
directly (the bundle schema's phase/tool/pack-promotion/etc. fields are
typed as bare `map?`/`vector?`, not as the domain sub-schemas), so once
`optional-key` and the compliance predicates it calls are external
requires, both remaining defs are independent — 1 layer, not the
originally-reported 6.

`interface.clj`'s Layer 7 schema-export pass-throughs
(`intent-types`, `semantic-validation-rules` → now sourced from
`schema.domain`; `data-classifications`, `regulatory-tag-values`,
`default-retention-days`, `default-data-classification` → now sourced
from `schema.compliance`) were repointed to the namespace each symbol
actually moved to. `create-evidence-template` is unchanged — it still
calls `schema/create-evidence-bundle-template`, which stayed in the root
namespace.

Same-component files (Polylith allows `.schema`/subnamespace references
from within the component; only cross-component deps must go through
`.interface`) that referenced moved symbols were repointed to the new
namespaces: `collector.clj` and 5 test files
(`schema_test.clj`, `schema_pack_promotion_test.clj`,
`assembly_integration_test.clj`, `compliance_metadata_test.clj`,
`collector_compliance_test.clj`) now require `schema.compliance` and/or
`schema.validation` and/or `schema.domain` alongside (or instead of)
`schema` itself, depending on which symbols they use.
`protocols/impl/evidence_bundle.clj` and
`protocols/impl/semantic_validator.clj` no longer require `schema` at
all — everything they used (`validate-schema`, `intent-schema`,
`semantic-validation-schema`, `semantic-validation-rules`) moved to
`schema.validation`/`schema.domain`.

No behavior change: same def names, same docstrings, same schema shapes,
same validation semantics. The only externally-visible change is that 5
predicates went from `defn-` to `defn` (required for the cross-namespace
reference from the root `schema.clj`) — nothing outside the component
referenced them under either visibility.

## Testing Plan

1. Read the full 477-line target file and `interface.clj` before touching
   anything; `grep -rn "ai.miniforge.evidence-bundle.schema"` across the
   whole repo to confirm no cross-component consumer requires `.schema`
   directly (Polylith only allows that from within the component) — only
   5 test files, `collector.clj`, and the two `protocols/impl/*.clj`
   files did, all inside this component.
2. Derived the real same-file dependency graph by hand (which def's body
   references which other same-file def) to design the split — not by
   running the linter's `--fix` (this is a real split, `--fix` only
   relabels a file's own existing top-level defs, it doesn't move code
   across files).
3. Ran the stratum linter, non-warn mode, over the whole component:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/evidence-bundle
   ```

   Exit 1, non-empty output — see "known pre-existing gap" below.
   `schema.clj`'s own `SL003` finding (was `schema.clj:409 file uses 7
   distinct layers`) is gone; confirmed by diffing this run's output
   against the same command run via `git stash` on the pre-change tree —
   every remaining finding (`collector.clj` 6 layers, `extraction.clj` 4
   layers, `interface.clj` 9 layers, `workflow_integration.clj` 4 layers,
   `assembly_integration_test.clj` SL002 decorative headers,
   `collector_compliance_test.clj` 6 layers) is byte-for-byte identical
   before and after this change — none of it was introduced here, and
   none of it is in scope for this PR (each is its own Wave 2 file).
4. `clj-kondo --lint components/evidence-bundle`: 0 errors, 0 warnings.
5. `bb test` (the monorepo-wide "changed-and-affected" suite) was started
   but not used as the completion signal: it pulled in
   `dag-executor.executor-test`, a slow, Docker-dependent test that was
   still running past 13 minutes with repeated "Docker not available"
   skip/retry cycles unrelated to this change, and this worktree shares
   the host with sibling agents already running their own concurrent
   `poly test`/`poly check` processes in other worktrees. Killed the
   stray process rather than let it fight for the same JVM/Maven-cache
   resources. Substituted per this task's own documented fallback:
   - Component tests, run directly (bypassing the slow change-detection
     wrapper) via `clojure -M:dev:test` against all 13 evidence-bundle
     test namespaces (including the 5 touched by this PR):
     **114 tests, 350 assertions, 0 failures, 0 errors, exit 0.**
   - Poly-aware structural check: `clojure -M:poly check` — exit 0. Two
     warnings only (`config`'s non-top-namespace test fixtures,
     `data-foundry`'s unused `decision-envelope` component dep), both
     pre-existing and unrelated to evidence-bundle.
6. Adversarial self-review of the full diff: checked every moved def kept
   its docstring, every `optional-key`/`validate-schema` call site was
   repointed to its new namespace and not left dangling, no namespace
   cycle was introduced (`schema.compliance` → `schema.validation` +
   `schema.optional-key`; `schema.domain` → `schema.optional-key`;
   root `schema` → `schema.compliance` + `schema.optional-key`; nothing
   points back up), `interface.clj` still contains only pass-throughs
   (no implementation logic moved into it), and re-verified
   `schema.clj`'s own diff shows only mechanical `optional-key` →
   `optional-key/optional-key` and `default-*` → `compliance/default-*`
   substitutions with no schema-shape change.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — same def names, docstrings, and validation semantics; only
namespace location and (for 5 previously-`defn-` compliance predicates)
visibility changed. Pre-commit's `lint:stratum` autofixer keeps this
component's headings honest going forward.

## Known pre-existing gap (not touched by this PR)

`bb -m stratum-lint.interface components/evidence-bundle` does not exit 0
after this change — 4 other `src` files (`collector.clj`, `extraction.clj`,
`interface.clj`, `workflow_integration.clj`) and 1 more test file
(`collector_compliance_test.clj`) still report genuine `SL003` over-budget
findings, plus `assembly_integration_test.clj` reports decorative-heading
`SL002` findings. All of these predate this PR (confirmed via
`git stash` diff of the linter's output) and are each their own Wave 2
follow-up, matching this baseline's per-file PR granularity.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Wave 1 tone/format reference:
  `docs/pull-requests/2026-07-25-fix-stratum-lint-wave1-fsm.md`
- Follow-on Wave 2 splits still open in this component: `collector.clj`
  (6 layers), `extraction.clj` (4 layers), `interface.clj` (9 layers,
  though interface files are exempt from the hard cap per rule 210 —
  worth a human call on whether it still warrants a split),
  `workflow_integration.clj` (4 layers), and the `collector_compliance_test.clj`
  / `assembly_integration_test.clj` heading cleanups.

## Checklist

- [x] Real dependency graph derived by hand before designing the split
- [x] Split by schema domain/grouping (optional-key utility / generic
      validator / compliance domain / N6 domain schemas / top-level
      composite), not by artificially avoiding the "schemas only"
      component-layout convention
- [x] `interface.clj` repointed to the namespaces each re-exported symbol
      actually moved to; still contains only pass-throughs
- [x] All same-component internal references (`collector.clj`, 2
      `protocols/impl/*.clj` files, 5 test files) repointed to the new
      namespaces
- [x] Each new/changed file verified ≤3 real layers, with `Layer N`
      headings and `^{:stratum n}` metadata matching rule 210's
      convention
- [x] Stratum linter run in non-warn mode; `schema.clj`'s own `SL003`
      finding confirmed cleared; all remaining findings confirmed
      pre-existing via `git stash` diff (documented above, not silently
      ignored)
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Evidence-bundle test suite green: 114 tests, 350 assertions, 0
      failures/errors (`clojure -M:dev:test`, direct run — `bb test`
      substituted per the documented fallback; reason given above)
- [x] `clojure -M:poly check` clean (0 errors; 2 pre-existing,
      unrelated warnings)
- [x] Adversarial self-review of the full diff for lost docstrings, stale
      requires, namespace cycles, or implementation leaking into
      `interface.clj`
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
