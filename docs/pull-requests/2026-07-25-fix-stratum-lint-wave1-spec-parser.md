# fix: stratum-lint autofix for components/spec-parser (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/spec-parser` (`src` + `test`)
to replace decorative `Layer N` section banners with real headings and
`^{:stratum n}` metadata derived from each file's actual same-file
reference graph. Mechanical, plus a small set of hand-fixes to stale
prose the tool cannot rewrite on its own (see below). One of the smaller
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`spec-parser` carried exactly 2 findings at baseline, both `SL003` (over
the 3-layer budget): `core.clj` and `interface.clj`, each reporting "4
distinct layers". Zero `SL001` findings, so no upward-reference/cycle
risk to reason about before running the mechanical fixer — matches the
Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/spec-parser
```

All 10 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings). The mechanical `--fix`
pass itself changed only heading text, `^{:stratum n}` metadata, and
def/deftest reordering — no executable code. (A review follow-up landed
one narrow, unrelated behavior fix on top of this same PR; see
"Review follow-up" below — that commit is the exception to this PR's
otherwise mechanical-only diff.)

The real reference-graph computation did not agree with the old
headings' claimed depth in either direction:

- `interface.clj` — old headings claimed 4 layers (the `SL003` trigger).
  Every fn here is a thin one-line pass-through to `core` or `schema`;
  none references another same-file def. Real depth is **1 layer**. The
  `SL003` finding is gone entirely.
- `core.clj` — old headings also claimed 4 (`SL003`), but the real chain
  is deeper than the old headings showed: `format-parsers` (a registry
  map referencing `parse-markdown`) and `parse-content`/`parse-spec-file`
  sit above where the 4-layer headings placed them. Real depth is
  **6 layers (0-5)**, worse than the old finding suggested, not better.
- `schema.clj` — carried no baseline finding (old headings claimed 3,
  exactly at budget). The real graph makes `SpecPayload`/`SpecInput`
  depend on `SpecIntent`/`SpecProvenance`, which depend on
  `intent-types`/`source-formats`, pushing real depth to **4 layers
  (0-3)** — a new over-budget finding the fix surfaced, not a
  pre-existing one.

Two hand-fixes beyond the mechanical `--fix` output, per the known
tool-limitation patterns from earlier Wave 1 batches:

1. **Stale layer-count prose in docstrings.** `core.clj`'s ns docstring
   and `escalate!`'s docstring both asserted "layer 0/1/2 fns return
   anomalies" — accurate against the old (wrong) 3-layer headings, false
   against the real 6-layer structure. Reworded both to describe the
   actual layer 0-4 anomaly-returning / layer 5 escalation-boundary
   split. `schema.clj`'s ns docstring likewise named its 3 old layers by
   the wrong content; reworded to the real 4-layer breakdown. Same fix
   applied to a copy of the same stale claim in
   `test/.../anomaly/parse_spec_file_test.clj`'s ns docstring.
2. **Redundant decorative test-group banners.** `interface_test.clj` had
   6 old single-semicolon `Layer N: <description>` banners (e.g. `Layer
   2: Defaults Tests`) left over from the cargo-cult pattern — every
   `deftest` here is now real `Layer 0` (no same-file dependencies
   between tests), so all 6 numbered claims were false. Each was
   redundant with its already-descriptive test name(s) (e.g.
   `defaults-test`, `schema-validation-helpers-test`), so all 6 were
   deleted rather than reworded, leaving the one real `Layer 0` heading
   the fixer inserted plus the pre-existing non-numbered comments
   (`;; Verify Malli schemas accept/reject correctly`, `;; Regression
   coverage for parse-markdown...`) that still carry real, accurate
   information. Confirmed stable: re-ran `--fix` after these edits and
   it made no further changes.

No same-line trailing-comment relocation issue applied here — this
component has none (all comments already own-line).

### Review follow-up

Two rounds of Copilot review comments landed on this PR and were folded
in as a second commit (`a6894c452`), separate from the mechanical `--fix`
commit above:

1. `parse-markdown`'s frontmatter/body merge used `(str % "\n\n" ...)`.
   Copilot claimed this produces a literal `"nil\n\n..."` description
   when frontmatter omits `:spec/description`. Verified empirically —
   false: `(str nil "\n\n" "body")` evaluates to `"\n\nbody"` in
   Clojure, since `str` coerces `nil` to `""` rather than the text
   `"nil"`. The real, smaller issue underneath: a stray leading blank
   line before the body in that case — cosmetic, not corrupted content.
   Fixed by making the nil-coercion explicit and trimming:
   `(str/trim (str (or % "") "\n\n" (str/trim body)))`. Added a
   regression test for the missing-frontmatter-description case.
2. `validate-spec`'s docstring (in both `core.clj` and its `interface.clj`
   re-export) claimed `:errors [...]` (a vector). Verified against
   `explain-spec-payload` (`schema.clj`): it returns
   `malli.error/humanize`'s output, a field-key -> messages map, not a
   vector. Corrected both docstrings.
3. A subsequent comment caught that this PR doc's "no executable code
   changed" line (above) was now stale given fix #1's behavior change —
   reworded to scope that claim to the mechanical `--fix` commit only.
4. A subsequent comment caught that `core.clj`'s ns docstring listed
   `namespace-frontmatter-keys` (the frontmatter key-mapping helper)
   under "Layer 0", but it's tagged `^{:stratum 1}` — it depends on
   `spec-key->ns` (the Layer 0 key -> namespace map), it isn't itself
   Layer 0. Corrected the docstring's layer breakdown to name
   `spec-key->ns` at Layer 0 and `namespace-frontmatter-keys` at Layer 1.

Each Copilot thread was replied to with the empirical finding (correct,
partially correct, or — for #3/#4 — direct fixes) before being resolved.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 2 `SL003` findings exactly (`core.clj`, `interface.clj`).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 10 changed files. Found and hand-fixed the
   two stale-prose issues above; re-ran `--fix` a third time afterward —
   still zero diff, confirming the hand-fixes are stable under the tool.
4. `clj-kondo --lint components/spec-parser`: 0 errors, 0 warnings.
5. Ran all 7 test namespaces directly via `clojure -A:test -e`: 36 tests,
   131 assertions, 0 failures, 0 errors.
6. After the review follow-up (nil-coercion fix + new regression test):
   re-ran `--fix` (zero diff, still idempotent), `clj-kondo` (still 0/0),
   and all 7 test namespaces again: 36 tests, 132 assertions (one more
   than before — the new regression test), 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix:
   - `interface.clj`'s `SL003` is gone (real depth 1, well under budget).
   - `core.clj`'s `SL003` remains, now reporting 6 layers (was 4) — same
     finding, worse number, since the fix corrected an undercount.
   - `schema.clj` now reports `SL003` at 4 layers — new, not present at
     baseline (was 3, at budget). Both remaining `SL003`s are genuine
     over-budget files needing a namespace split, out of scope for this
     mechanical Wave 1 PR — Wave 2 territory.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component's headings honest going
forward; the two `SL003`s stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
`core.clj` and `schema.clj`.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/spec-parser/src/ai/miniforge/spec_parser/core.clj` (6 real
  layers) and `.../schema.clj` (4 real layers, newly surfaced)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 10 changed files
- [x] Stale layer-count docstring prose (3 files) hand-fixed to match
      real computed depth; confirmed stable under a third `--fix` pass
- [x] 6 redundant decorative `Layer N: <description>` test banners
      (`interface_test.clj`) deleted; real descriptive comments kept
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (36 tests, 131 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains on `core.clj` (6
      layers, worse-than-baseline number for the same finding) and
      `schema.clj` (4 layers, newly surfaced) — both documented above,
      tracked as Wave 2
- [x] Review follow-up: nil-coercion whitespace artifact + 2 stale
      docstrings fixed, regression test added, all Copilot threads
      replied to with empirical findings and resolved
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
