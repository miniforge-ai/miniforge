# fix: stratum-lint autofix for components/phase-software-factory (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/phase-software-factory` (`src` +
`test`) to replace decorative `Layer N` headings with headings and
`^{:stratum n}` metadata derived from each file's actual same-file reference
graph. Mechanical `--fix` pass with one hand-corrected comment
misattachment (a known tool limitation — see Testing Plan) in a test file;
no behavior change anywhere. One of the Wave 1 per-component PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

A plain (non-`--fix`) `stratum-lint` run over this component before any
change reported only 3 findings, all `SL002` in `plan.clj` (a repeated
`Layer 1` banner). Zero `SL001` findings, so no upward-reference/cycle risk
to reason about before running the mechanical fixer — this component is in
scope for Wave 1.

That low pre-fix count undersold the real debt. Of the 13 `src` files, 8 had
either zero `Layer` headings at all (`knowledge_helpers.clj`, `messages.clj`,
`phase_config.clj`, `phase_terminal.clj`, `review_convergence.clj` — silently
skipped by the tool, a documented limitation, not a pass) or 3 headings that
looked compliant — monotonic, within the 3-layer budget — but didn't reflect
the file's true reference depth (`implement.clj`, `release.clj`, `review.clj`,
`phase_handoff.clj`, `verify.clj` — the last of these also had a stray,
non-numeric `Layer 0.5` heading the tool doesn't parse as valid). This is the
same "headings as decoration, not structure" pattern the baseline document
diagnoses at the workspace level, just less extreme than its worst-offender
example.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/phase-software-factory
```

28 files rewritten (13 `src`, 15 `test`) — `--fix` normalizes every file in
the component, not just the ones with findings. No line of executable code
changed; diffs are heading text, `^{:stratum n}` metadata, and def/deftest
reordering only (verified by normalizing both versions — stripping blank
lines, heading-comment lines, and `^{:stratum n}` tags, then sorting and
diffing the remainder — zero diff on every `src` file).

One hand-fix was required. `--fix` displaced a same-line trailing comment
in `implement_test.clj`: `; 11 min` originally annotated the end of
`rate-limit-classifier-does-not-flag-legitimate-curator-failures-test`
(`:metrics {:duration-ms 660000}})))))  ; 11 min`), documenting that
660000ms curator-no-files case as a real task failure, not an infra hiccup.
After `--fix` reordered the file, the comment landed on the closing paren of
the unrelated `use-fixtures` form instead — a known tool limitation
(same-line trailing comments aren't associated with their preceding def by
the tool's rewriter). Restored the comment to its original deftest by hand,
then ran `--fix` a third time: zero rewrites, confirming the correction is
stable.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the 3-finding
   baseline (`SL002` × 3, `plan.clj`) exactly; confirmed 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 28 changed files. Grepped every changed file's
   pre-fix content for same-line trailing comments (`[^;]\)+ *;`); found and
   hand-fixed the `implement_test.clj` misattachment described above. No
   stale double-semicolon `;;---- Layer N: <description>` banners survived
   anywhere (`grep -rn '^;;----.*Layer'` over the component: zero matches).
   Each file with a `defmethod` (`explore.clj`, `plan.clj`, `pr_monitor.clj`,
   `review.clj`, `verify.clj`, `implement.clj`, `release.clj`) has exactly
   one `defmethod` for `phase/get-phase-interceptor-method`, so the
   multi-`defmethod`-inconsistent-stratum bug class doesn't apply here.
   Re-ran `--fix` a third time after the hand-fix: zero diff.
4. `clj-kondo --lint components/phase-software-factory`: 0 errors, 1
   warning (`Unused private var
   ai.miniforge.phase-software-factory.verify/verdicts`), confirmed
   pre-existing via `git stash` against the pre-fix content — same warning,
   same var, only the line number shifted (362 → 113) from reordering. Not
   introduced by this change; left alone, matching how a prior Wave 1 PR
   (`connector-http`) handled an identical pre-existing-warning situation.
5. Ran the component's tests via `clojure -M:poly test brick:phase-software-factory`
   (the plain `clojure -A:test` invocation from the component directory
   doesn't resolve cleanly — `workflow`, a dependency of this component, is
   itself missing a `pr-train` dependency declaration in its own `deps.edn`,
   and a Polylith-scoped test run also needs `phase`'s `test`-only
   `loader-support` helper on the classpath; both are pre-existing
   conditions in other components, unrelated to this fix, and out of scope
   here). All 15 test namespaces, 214 tests, 596 assertions, 0 failures, 0
   errors — run twice (once per project that includes this brick:
   `miniforge` and `miniforge-tui`), same result both times.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004` all
   clear. `SL003` now reports on 10 files that had zero findings in the
   pre-fix baseline — every one newly surfaced by `--fix` inferring real
   reference depth, not a pre-existing flagged finding:
   - `phase_handoff.clj` — 10 real layers
   - `implement.clj`, `release.clj` — 6 real layers each
   - `review.clj`, `verify.clj`, `release_test.clj` — 5 real layers each
   - `phase_config.clj`, `plan.clj`, `review_convergence.clj`,
     `review_repair_loop_test.clj` — 4 real layers each

   All tracked as Wave 2 (real namespace splits), consistent with how prior
   Wave 1 PRs in this program handled the same situation. `phase_handoff.clj`
   at 10 layers is the deepest surfaced in this component and worth
   prioritizing in Wave 2 triage.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only, plus the one comment-position
correction (also non-executable). Pre-commit's `lint:stratum` autofixer
keeps this component clean going forward; the 10 `SL003` files stay
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2
splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `phase_handoff.clj` (10 layers),
  `implement.clj`/`release.clj` (6 each), `review.clj`/`verify.clj`/
  `release_test.clj` (5 each), `phase_config.clj`/`plan.clj`/
  `review_convergence.clj`/`review_repair_loop_test.clj` (4 each)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 28 changed files; one same-line trailing
      comment misattachment found and hand-fixed (`implement_test.clj`);
      third `--fix` pass confirms the fix is stable (zero diff)
- [x] No stale decorative `;;----` banners survived
- [x] No multi-`defmethod` stratum-inconsistency risk (one `defmethod` per
      file, each in a different file)
- [x] `clj-kondo` clean (0 errors; 1 pre-existing warning, unaffected by
      this change)
- [x] Component tests pass via `poly test brick:phase-software-factory`:
      214 tests, 596 assertions, 0 failures/errors (both projects)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; 10 newly
      surfaced `SL003` findings documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
