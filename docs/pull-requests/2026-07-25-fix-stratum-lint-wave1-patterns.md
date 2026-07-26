# fix: stratum-lint autofix for components/patterns (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/patterns` (src + test) and
commits the result: regenerated `;---- Layer N` headings and
`^{:stratum n}` metadata on every top-level def, computed from each
file's real same-file reference graph. No logic changed. One component
in the Wave 1 batch from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The baseline audit found `components/patterns/src/.../core.clj` tripping
`SL003` (4 distinct layers under its old, decorative headings — over the
3-layer budget from rule 210, `standards/miniforge/languages/clojure.mdc`)
and zero `SL001` upward-reference findings — the criterion the baseline
plan uses to mark a file safe for a mechanical autofix pass with no
cycle/reasoning risk.

## Changes in Detail

Ran, pinned to the sha in `tasks/stratum.clj`
(`80699e378cb8ebbb6daeb928431aa4a6b373c07e`):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/patterns
```

All 3 files in the component were rewritten: `core.clj`, `interface.clj`,
`test/.../interface_test.clj`. `core.clj`'s defs are a set of standalone
regex literals with no same-file cross-references, so the real reference
graph collapses its old 4 decorative headings (`Layer 0`-`3`, one per
loose "section") to a single real `Layer 0` — the SL003 over-budget
reading was itself decorative, not a genuine depth problem. No defs were
reordered; only headings were merged and `^{:stratum 0}` metadata added
throughout all three files.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's single finding exactly: `SL003` on `core.clj` ("file uses 4
   distinct layers (max 3)"), zero `SL001`.
2. Ran `--fix`, then read the full diff for all 3 changed files. Confirmed
   heading merge and `^{:stratum n}` metadata only — no def reordering, no
   comment relocation, license headers unchanged.
3. Ran `--fix` a second time: zero output, zero diff against the first
   pass — confirmed idempotent.
4. Ran `clj-kondo` over `components/patterns`: 0 errors, 0 warnings.
5. Re-ran plain `stratum-lint` after the fix: exit 0, no findings remain.
   The prior `SL003` does not persist — the file's real stratum count is
   1, not 4 — so there is no Wave 2 follow-on for this component.
6. Ran `components/patterns/test/.../interface_test.clj` directly
   (`clojure -M -e` requiring the namespace and invoking
   `clojure.test/run-tests`): 4 tests, 9 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. Comment/metadata/
heading-only diff — no runtime behavior change, nothing to roll out
beyond the merge. The pre-commit hook's `lint:stratum` autofixer keeps
this component clean going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
- No Wave 2 follow-on: this component's `SL003` fully resolved by the
  fix, no real over-budget file remains.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Full diff read for all 3 changed files; heading + metadata only, no
      reordering, no comment relocation
- [x] Idempotency verified: second `--fix` pass produced zero diff
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Plain lint re-run post-fix: zero findings remain (no residual
      `SL003`)
- [x] Component test namespace run directly: 0 failures, 0 errors
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
