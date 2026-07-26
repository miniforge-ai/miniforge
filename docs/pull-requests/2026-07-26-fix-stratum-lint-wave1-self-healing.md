# fix: stratum-lint autofix for components/self-healing (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/self-healing` (`src` + `test`)
to replace decorative `Layer N` banners and missing headings with real
`Layer N` headings and `^{:stratum n}` metadata derived from each file's
actual same-file reference graph. Mechanical: no logic changes. Also
hand-fixes four stale `;;---- Layer N Tests` banners that `--fix` left
behind in one test file, still claiming layer numbers that no longer
match the recomputed real stratum (see Changes in Detail) — a
comment-only correction, not a behavior change. One of the Wave 1 batch 4
per-component PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/self-healing` reported
only `SL003` (over the 3-layer budget), zero `SL001`/`SL002`/`SL004`:

```text
backend_health.clj:378:1: SL003 file uses 4 distinct layers (max 3)
integration.clj:198:1: SL003 file uses 4 distinct layers (max 3)
stream_recovery.clj:306:1: SL003 file uses 5 distinct layers (max 3)
workaround_detector.clj:327:1: SL003 file uses 6 distinct layers (max 3)
```

Zero `SL001`, confirming this component carries no upward-reference/cycle
risk requiring human triage before running the mechanical fixer.
`interface.clj` and `workaround_registry.clj` reported no findings at all
pre-fix, but both used the cargo-cult pattern the baseline describes:
`interface.clj` grouped its re-exports under double-semicolon named-section
banners (`Workaround Registry`, `Backend Health`, etc.) with no real
`Layer N` heading anywhere, and `workaround_registry.clj` had only two
distinct heading values — both invisible to the checker under the
documented "no heading = silently skipped" limitation, not a clean bill of
health.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/self-healing
```

All 10 `.clj` files in the component were rewritten (6 `src`, 4 `test`).
Diffs are heading text, `^{:stratum n}` metadata, and def/deftest
reordering only — no executable line changed. Two examples of the
cargo-cult diagnosis confirmed here:

- `workaround_detector.clj`'s `fetch-workaround-from-github` sat under an
  old decorative "Layer 5" banner ("GitHub issue integration") purely
  because it was written last. It has zero same-file references, so
  `--fix` correctly placed it at real Layer 0, alongside the file's other
  self-contained helpers.
- `workaround_registry_test.clj` had old double-semicolon banners reading
  "Layer 0 Tests" / "Layer 1 Tests" / "Layer 2 Tests" / "Layer 3 Tests"
  before each `deftest` group. None of those tests reference each other or
  any same-file helper beyond `test-registry-path` (real stratum 0), so
  every one of them landed at real Layer 0 — the old banners' numbers
  (1, 2, 3) no longer matched anything and sat confusingly between the
  file's real Layer 0 and Layer 1 headings. Hand-fixed: dropped the
  `Layer N Tests` claim from all four, keeping each still-accurate
  description (`Basic load/save operations`, `Add workaround`, `Update
  statistics`, `Query operations`) as a plain comment. Re-ran `--fix`
  afterward — zero diff, confirms the manual edit didn't interact with
  stratum computation (comments aren't part of the reference graph).

No same-line trailing comment was displaced onto the wrong def — grepped
the diff for the known `foo])  ; comment` migration pattern; no matches.
One pre-existing oddity survives unchanged in `stream_recovery.clj`: a
`;; => [...]` illustrative-output comment after the file's `(comment ...)`
block was already outside that form before the fix (the form closes one
line earlier); `--fix` only dedented it. Not a func­tional change and not
attached to any def.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the four `SL003`
   findings above exactly, zero `SL001`/`SL002`/`SL004`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 10 changed files. Confirmed only heading
   text, `^{:stratum n}` metadata, and def/deftest reordering changed.
   Found and hand-fixed the four stale `Layer N Tests` banners described
   above; re-ran `--fix` a third time afterward — zero diff, confirming
   stability.
4. `clj-kondo --lint components/self-healing`: 0 errors, 0 warnings.
5. Ran the full component test suite (`clojure -M:test -m
   cognitect.test-runner` from `components/self-healing`): 64 tests, 123
   assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on four files, with real layer counts now
   precisely measured from the true reference graph instead of the old
   decorative headings:
   - `backend_health.clj`: 4 → **7** real layers (old headings
     under-counted — several distinct strata had been merged under the
     same heading number). Same underlying over-budget finding, count
     increased because the old count was wrong.
   - `stream_recovery.clj`: 5 → **4** real layers (old headings had one
     extra, unneeded split). Same finding, count decreased.
   - `workaround_detector.clj`: 6 → **5** real layers (same pattern, one
     fewer than the old headings claimed).
   - `workaround_registry.clj`: **new** finding — 0 pre-fix (the old
     headings had only 2 distinct values, coincidentally under budget);
     true depth is 4 real layers, surfaced only once `--fix` recomputed
     from the actual reference graph.
   - `integration.clj`: **resolved** — old headings claimed 4 layers, true
     depth is 3, within budget. No longer reported.

   All four remaining `SL003` findings are real over-budget files (Wave 2
   scope: namespace split), not addressed here.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; the four `SL003`
files stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time)
until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `backend_health.clj` (7 real
  layers), `stream_recovery.clj` (4), `workaround_detector.clj` (5), and
  `workaround_registry.clj` (4) — all over the 3-layer budget.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 10 changed files; mechanical-only
- [x] Stale `Layer N Tests` banners in `workaround_registry_test.clj`
      hand-fixed (comment-only); third `--fix` pass confirms stability
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (64 tests, 123 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains on 4 files, documented
      above with precise before/after counts, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
