# fix: stratum-lint autofix for components/agent-runtime (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/agent-runtime` (`src` + `test`)
to replace cargo-culted `Layer N` headings with real ones derived from
each file's actual same-file reference graph, plus `^{:stratum n}`
metadata on every def. One of the smaller per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

Mostly mechanical, with one manual cleanup: `error_classifier_test.clj`
carried four decorative section banners (`Layer 0 Tests`, `Layer 1
Tests`, `Layer 2 Tests`, `Integration Tests`) that `--fix` left in place
because they don't match the tool's own generated heading shape. Left
untouched, they'd contradict the real headings the fix just added — every
`deftest` in this file is genuinely stratum 0 (each only calls the public
interface, none call each other), so banners implying a Layer 0→1→2
progression across the file are false. Hand-removed the four banner
lines, keeping the plain descriptive comment under each (e.g. `;; Message
formatting`) since those still carry real grouping information. No test
code changed — comment text only.

## Motivation

`components/agent-runtime` carried 3 findings under the baseline's
cargo-cult diagnosis, all in one file:
`error_classifier/core.clj` — 2× `SL004` (`retry-type->failure-class` and
`->failure-class` sat above the file's first `Layer` heading) and 1×
`SL003` (4 distinct layers against the 3-layer budget). Zero `SL001`
findings component-wide, confirmed by a plain lint run before touching
anything — no upward-reference/cycle risk to reason about first, matching
the Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/agent-runtime
```

6 files rewritten — `--fix` normalizes every file in the component, not
just the ones with findings:

- `error_classifier/core.clj` — `retry-type->failure-class` (Layer 0),
  `->failure-class` (Layer 1, calls the map above), `classify-error`
  (Layer 2, calls `->failure-class` plus four Layer-0 helpers). Real
  depth 3, within budget; the old headings had claimed 4 (0–3) by
  counting per-function-group instead of per real dependency level.
- `error_classifier/messages.clj` — `add-suggestions` moved from an
  unheaded trailing position to Layer 0 (it's a leaf, no same-file
  calls); the three `format-*-error` functions landed at Layer 1
  (they call `format-completed-work-section`); `format-error-message`
  at Layer 2.
- `error_classifier/patterns.clj` — real depth is 4 (`load-pattern-config`
  / `compile-pattern` / `matches-pattern?` at Layer 0 → `load-patterns` at
  Layer 1 → the four pattern `def`s at Layer 2 → `classify-by-patterns` at
  Layer 3), one deeper than the old 3-heading version, which happened to
  look compliant (monotonic, ≤3) while under-counting the true chain.
  This surfaces a **new** `SL003` — see below.
- `error_classifier/reporting.clj` — real depth 3 (0–2), matches the old
  heading count; only metadata and reordering changed.
- `interface.clj` — pure re-export file; every `def` is a bare alias to
  another namespace's var, so all collapse to Layer 0. The old headings
  (0/1/2) had grouped aliases by topic, not by real depth.
- `error_classifier_test.clj` — all 12 `deftest`s are real Layer 0 (no
  test calls another test); one bare `Layer 0` heading added before the
  first `deftest`, which previously sat above any heading at all. See
  Overview for the hand-fix to the four stale banners this exposed.

No line of executable/test logic changed anywhere; diffs are heading
text, `^{:stratum n}` metadata, def/deftest reordering, and (in the test
file) the four hand-removed stale banner lines.

`patterns.clj` now reports `SL003`: 4 real layers (0–3) against the
3-layer budget. This is a **newly surfaced** finding, not the same one
masked before — the pre-fix headings only claimed 3 layers and were
monotonic, so the plain lint baseline reported zero findings for this
file at all. `--fix` inferring the true reference chain reveals a real
depth of 4. Deferred to Wave 2 (real namespace split), consistent with
how prior Wave 1 PRs handled the same situation.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 3 findings exactly (2× `SL004`, 1× `SL003`, all in
   `core.clj`), and confirmed 0 `SL001` findings component-wide before
   proceeding.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 6 changed files. Found the stale-banner
   pattern in `error_classifier_test.clj` (see Overview); hand-fixed it,
   then ran `--fix` a third time — zero diff, confirms the hand-fix is
   stable. No same-line trailing comment was displaced onto the wrong def
   in any file.
4. Confirmed no `defmethod`s in this component — the multimethod-stratum
   bug class doesn't apply here.
5. `clj-kondo --lint components/agent-runtime`: 0 errors, 0 warnings.
6. Ran `ai.miniforge.agent-runtime.error-classifier-test` directly via
   `clojure -A:test -e`: 12 tests, 91 assertions, 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` remains, newly surfaced on `patterns.clj` (4 real
   layers) — expected, tracked as Wave 2, not a defect in this PR.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only in source and tests. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`patterns.clj`'s `SL003` stays advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/agent-runtime/src/ai/miniforge/agent_runtime/error_classifier/patterns.clj`
  (4 real layers, over the 3-layer budget — newly surfaced by this fix,
  not present in the original baseline)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 6 changed files
- [x] Stale decorative banners in the test file hand-fixed; third `--fix`
      pass confirms the hand-fix is stable (zero diff)
- [x] No `defmethod`s in this component (multimethod-stratum bug class
      not applicable)
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (12 tests, 91 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003`
      (`patterns.clj`, newly surfaced, documented above, tracked as
      Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
