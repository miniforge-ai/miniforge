<!--
  Title: Fix stratum-lint autofix for components/policy-pack (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/policy-pack (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/policy-pack` (`src` + `test`) to
replace decorative `Layer N` headings with real ones + `^{:stratum n}`
metadata derived from each file's actual same-file reference graph, per
rule 210 (`standards/miniforge/languages/clojure.mdc`). One of the
per-component Wave 1 batches from `work/stratum-lint-baseline-2026-07-24.md`
(batch 6) — the largest component fixed in this batch (37 baseline
findings).

## Motivation

`components/policy-pack` carried 37 baseline findings — 32 `SL002`, 5
`SL003`, 0 `SL004`, **zero `SL001`** — so no upward-reference/cycle risk
needed triage before running the mechanical fixer. A plain (non-`--fix`)
lint run before touching anything reproduced 37 findings exactly (32/5/0/0
by check), confirming the count hadn't drifted since the baseline was
taken and clearing this component for the mechanical pass per the task's
"confirm zero SL001 first" gate.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/policy-pack
```

All 31 `src` files and all 24 `test` files rewrote (55 files total). No
`SL008` refusal — no reader-conditional-wrapped defn hit that class of
issue in this component.

- Twelve `src` files' ns docstrings previously made a "how many layers"
  claim that went stale once `--fix` recomputed the real per-file
  structure, hand-corrected to describe the real grouping: `core.clj` (7
  real layers, was documented as 3), `detection.clj` (6, was 3),
  `external.clj` (5, was 3), `intent.clj` (4, was 3), `mapping.clj` (5, was
  3), `mdc_compiler.clj` (9, was 3), `prompt_template.clj` (6, was 3),
  `registry.clj` (5, was 3), `repair.clj` (4, was 3 — also inverted:
  built-in repair fns are real Layer 0, ahead of the registry functions
  the old docstring listed first), `schema.clj` (6, was 3), `taxonomy.clj`
  (4, was 3 — the old "Layer 2" description named a function,
  `export-canonical-taxonomy`, that actually lives in `mdc_compiler.clj`,
  not this file), `rules/pack_dependency_validation.clj` (6, was 4).
- `builtin_detectors.clj` (4 real layers), `compiler.clj` (8),
  `knowledge_safety.clj` (5): also over budget, but neither ns docstring
  made a layer-count claim to begin with — headings + metadata only, no
  docstring change needed.
- `mdc_compiler.clj` also had one decorative `;---- Layer 0.5` banner (a
  non-integer heading stratum-lint's regex doesn't recognize, so it wasn't
  touched by `--fix`) sitting inside what's now a single real `Layer 0`
  block. Dropped the heading line by hand, keeping its descriptive text
  ("Detection and remediation config builders") as a plain comment. Same
  class of fix as prior Wave 1 batches.
- Test files: five files carried leftover decorative `;; Layer N — <label>`
  section comments (22 lines total) whose numbers no longer matched the
  real, now-computed heading boundaries around them —
  `mdc_compiler_test.clj` (8 lines), `schema_test.clj` (5), `taxonomy_test.clj`
  (4), `mdc_to_pack_mapping_test.clj` (3), `prompt_template_test.clj` (2).
  In every case the real structure collapsed several old "layers" of
  `deftest` groups into one real `Layer 0` (or `Layer 1`), since the tests
  in each group don't call each other in-file; the leftover comments still
  named the old, wrong layer numbers (including some that happened to
  still match, which is just as redundant once the real heading already
  states it). Stripped the `Layer N —` prefix from all 22, keeping the
  descriptive label text as a plain comment.

No change in runtime behavior anywhere in this diff — heading text,
`^{:stratum n}` metadata, def/deftest reordering, comment cleanup, and (12
files, listed above) ns docstring corrections to match the real,
now-computed layer structure. Verified structurally: reading both the
pre-fix and post-fix version of all 55 files with the real Clojure reader,
stripping all metadata, and comparing the resulting forms as
order-independent multisets shows **zero** difference in any file except
the 12 ns docstrings that were deliberately hand-edited — proof `--fix`
and the hand edits never touched a function body, only comments, metadata,
docstrings, and top-level order.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before touching anything —
   reproduced the baseline's exact 37 findings (32 `SL002`, 5 `SL003`, 0
   `SL004`, 0 `SL001`).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff
   (no "rewrote" output at all on the second pass), confirms idempotency.
3. Read the full diff for all 55 changed files, plus a scripted scan for
   leftover decorative `Layer N` comments across every changed file (not
   just the diff's added lines, since pre-existing decorative comments
   that `--fix` doesn't touch show up as unchanged context, not a diff
   hunk). Found and hand-fixed one decorative non-integer heading
   (`mdc_compiler.clj`'s `Layer 0.5`) and 22 leftover decorative
   `Layer N —` label comments across 5 test files, plus 12 stale
   ns-docstring layer-count claims, all described above.
4. Re-ran `--fix` a third time after all hand edits — zero diff, still
   idempotent.
5. Wrote a babashka script that reads both the git `HEAD` and working-tree
   version of every changed file with `clojure.core/read`, strips all
   metadata via `clojure.walk/postwalk`, and compares the resulting forms
   as frequency-counted multisets (order-independent). All 55 files match
   exactly except the 12 files with deliberate ns-docstring edits — a
   direct, tool-independent proof that no function/def body changed.
6. `clj-kondo --lint components/policy-pack`: 0 errors, 0 warnings.
7. Ran all 24 test namespaces directly via `clojure -M:dev:test` (from the
   repo root, so the `standard_packs_test.clj` check against the built
   `components/phase/resources/packs/miniforge-standards.pack.edn`
   resolves correctly): **266 tests, 2552 assertions, 0 failures, 0
   errors.**
8. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004` clear
   everywhere. `SL003` remains on 18 findings (16 `src` files, 2 `test`
   files) — newly surfaced or confirmed by `--fix`'s accurate
   reference-graph computation, not something `--fix` itself can resolve.
   Deferred to Wave 2 (real namespace split), consistent with how prior
   Wave 1 batches handled the same situation.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Committed with
`MINIFORGE_STRATUM_BUDGET_MODE=warn` (alongside
`MINIFORGE_COMMIT_BUDGET_OVERRIDE=1`) so the remaining `SL003` findings
print as warnings instead of blocking the commit. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; the 18
findings below stay advisory until Wave 2 splits their namespaces.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1, batch 6)
- Follow-on: Wave 2 namespace split for `components/policy-pack/src/ai/miniforge/policy_pack/{builtin_detectors,compiler,core,detection,external,intent,knowledge_safety,loader,mapping,mdc_compiler,prompt_template,registry,repair,schema,taxonomy}.clj`,
  `rules/pack_dependency_validation.clj` (4-9 real layers each, over the
  3-layer budget), and `test/ai/miniforge/policy_pack/{mdc_to_pack_mapping_test,overlay_test}.clj`

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second (and third, post-manual-edit) `--fix` pass confirms
      idempotency (zero diff)
- [x] Diff read in full for all 55 changed files; mechanical, plus one
      decorative-heading removal, 22 leftover decorative test-comment
      fixes, and 12 hand-corrected stale docstring claims
- [x] Reader-level form-equality check (metadata stripped) confirms zero
      code-body changes anywhere outside the 12 deliberate docstring edits
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (266 tests, 2552 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003` on 18
      findings (documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
