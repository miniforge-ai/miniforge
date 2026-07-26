# fix: stratum-lint autofix for components/bb-data-plane-http (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/bb-data-plane-http` (`src` +
`test`) to replace two pre-heading `def`s with real `Layer N` headings and
`^{:stratum n}` metadata derived from the file's actual same-file
reference graph. Mechanical, plus a hand-fix of two now-stale `Layer N`
claims left in ns docstrings by the fixer (see below). One of the smaller
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`bb-data-plane-http` carried exactly two findings under the baseline's
cargo-cult diagnosis, both `SL004` on `core.clj`: `default-base-url` and
`default-base-url-env` sat above the file's first `Layer` heading. Zero
`SL001` findings, so no upward-reference/cycle risk to reason about before
running the mechanical fixer — matches the Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/bb-data-plane-http
```

3 files rewritten: `core.clj`, `interface.clj` (both `src`), and
`core_test.clj` (`test`) — `--fix` normalizes every file in the component,
not just the two files with findings.

`core.clj`'s real reference graph split into 3 layers, different from what
its old (honest-looking, but wrong) headings claimed: Layer 0 is
`resolve-base-url`/`under-root`/`build-cargo-cmd`/`wait-ready!`/`destroy!`/
the HTTP helpers (no same-file deps); Layer 1 is `binary-path`/
`manifest-path` (both call `under-root`); Layer 2 is `build!`/`start!` (the
process lifecycle, calling the Layer 1 path builders and `build-cargo-cmd`).
The old headings had labeled these groups Layer 0 (config + builders),
Layer 1 (lifecycle), Layer 2 (HTTP) — same three buckets, wrong contents
and wrong order relative to the real dependency direction.

`interface.clj` collapsed to a single Layer 0: every def here is a
thin pass-through to `core`, so none has a same-file dependency on another
def in this file. `core_test.clj` likewise collapsed to Layer 0 throughout
— `deftest` bodies call `sut/...` (a different namespace), not each other.
No line of executable code changed in any of the three; diffs are heading
text, metadata, and def/deftest reordering only.

**Hand-fix beyond the tool's own patch:** both `core.clj`'s and
`core_test.clj`'s namespace docstrings stated the old (wrong) per-layer
breakdown in prose — not a comment banner, but the same root problem as
the documented "stale decorative banner" tool limitation: `--fix`
recomputes headings and metadata, but has no way to know a docstring is
describing them too, so it left both untouched and now-false. Reworded
both to match the real layering (see diffs) rather than deleting the
content — it carries real information (which layer does what) that a
bare `Layer N` heading doesn't. Re-ran `--fix` a third time afterward:
zero diff, confirms the manual wording change didn't disturb anything the
tool manages.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's two `SL004` findings exactly.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 3 changed files. Confirmed no same-line
   trailing comment was displaced onto the wrong def (this component has
   none — all comments were already own-line). Found the stale-docstring
   issue described above, hand-fixed it, then re-ran `--fix` a third time
   to confirm the edit is stable (zero diff).
4. `clj-kondo --lint components/bb-data-plane-http`: 0 errors, 0 warnings.
5. Ran `ai.miniforge.bb-data-plane-http.core-test` directly via
   `clojure -M:test -m cognitect.test-runner`: 17 tests, 38 assertions, 0
   failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: zero findings across all
   four rule checks. No `SL003` — the real layer counts for this
   component (3 for `core.clj`, 1 for the other two files) are within the
   3-layer budget, so nothing is deferred to Wave 2.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comments, metadata, docstring wording, and def order only.
Pre-commit's `lint:stratum` autofixer keeps this component clean going
forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- No Wave 2 follow-on needed for this component — post-fix lint is clean.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 3 changed files
- [x] Stale `Layer N` docstring claims (tool limitation, not this fix's
      doing) hand-corrected; third `--fix` pass confirms stability
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (17 tests, 38 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings, no `SL003` remaining
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
