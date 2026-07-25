# fix: stratum-lint autofix for components/release-executor (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/release-executor` (`src` +
`test`) to replace decorative `Layer N` headings with real ones derived
from each file's actual same-file reference graph. Mechanical: no logic
changes. One component from `work/stratum-lint-baseline-2026-07-24.md`'s
Wave 1 batch 3.

## Motivation

`release-executor` carried exactly 2 findings under the baseline's
cargo-cult diagnosis, both `SL002` in `git.clj` (a repeated `Layer 0`
heading, and a repeated `Layer 1` heading — decorative section breaks,
not real stratum boundaries). Zero `SL001` findings for the component, so
it matched Wave 1's selection criteria: no upward-reference/cycle risk to
reason about before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component, at stratum-lint pin
`14965e1ee1a175bd00f637d9a9d5f7d27e62b73f` (bumped in #1501):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/release-executor
```

17 files rewritten (`--fix` normalizes every already-annotated file in the
component, not just the 2-finding one): `core.clj`, `files.clj`, `git.clj`,
`interface.clj`, `messages.clj`, `metadata.clj`, `result.clj`, `sandbox.clj`
(`src`), and 9 test files. `git.clj`'s two flagged headings collapsed into
the file's real `Layer 0`/`Layer 1`/`Layer 2` structure. Elsewhere: pure
heading/metadata/reordering, no executable-code changes.

### A `defmethod`-handling bug in `--fix`, found and fixed upstream mid-PR

An earlier pass at the previous pin (`80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
surfaced a real `--fix` defect: `files.clj`'s `process-file-action` (a
`defmulti` + 4 `defmethod`s) genuinely calls `path-traversal-anomaly` and
`write-file!`, both defined later in the file, but `--fix` placed
`process-file-action` at `Layer 0` — below its own callees. This was
self-inconsistent (the plain lint's own `SL001` check flagged the `--fix`
output it had just produced) and broke `clj-kondo` with real
`Unresolved symbol` forward-reference errors, since `--fix` physically
reorders defs by its computed layer.

Root cause (per upstream triage): `infer-levels`'s dependency graph was
keyed by def name via `into {}` — every `defmethod` for one multimethod
shares the multimethod's own name, so a repeated key meant `into {}` kept
only the *last* `defmethod`'s refs, discarding the earlier ones'.
`process-file-action`'s `:default` method (last in file, refs nothing)
silently overwrote `:create`/`:modify`/`:delete`'s real refs into
`path-traversal-anomaly`, so the multimethod was computed as a leaf and
placed before what it actually calls.

Fixed upstream in
[stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14)
(union refs per name instead of overwrite; regression test added and
verified to actually catch the bug), pin bumped in #1501. Re-ran this
component's fix fresh at the new pin (full re-baseline, not just
`files.clj`) — `process-file-action` now correctly lands at `Layer 3`,
after `path-traversal-anomaly` (`Layer 2`) and `write-file!` (`Layer 1`).
`clj-kondo` is clean on `files.clj` and the rest of the component.

### Hand-fixed: stale non-integer heading banners

Three files (still, at the new pin — unrelated to the `defmethod` bug
above) carry a pre-existing, non-standard `Layer 1.5` / `Layer 0b`
heading that `--fix`'s heading parser doesn't recognize (it only matches
bare integers), so it leaves them untouched, now sitting between the
tool's new real `Layer 0`/`Layer 1` headings — non-monotonic and
confusing (`0 → 1.5 → 1 → 2` in `git.clj` and `sandbox.clj`; `0 → 0b → 1
→ ...` in `metadata.clj`). In all three cases the descriptive text (`PR
creation helpers (mirrors sandbox.clj equivalents)`, `Diff inspection...`,
`Workflow data extraction`) was worth keeping as a plain grouping comment
— matches the style of other same-layer grouping comments already in
those files — so only the false `Layer N` claim was deleted, not the
description. Re-ran `--fix` after each edit: stable (zero diff on a
subsequent pass).

### Unrelated pre-existing clj-kondo warning

`core_sandbox_test.clj` had one pre-existing `unused binding opts-seen`
warning (present before this fix too, just at a different line after
reordering) — a dead `let` binding never referenced in its test body.
Removed it so the component lints clean, per this PR's own bar.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 2 `SL002` findings in `git.clj` exactly, 0 elsewhere.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 17 changed files. Found and hand-fixed the
   stale `Layer 1.5`/`Layer 0b` banners (3 occurrences, 3 files); no
   same-line trailing comment was displaced onto the wrong def elsewhere.
4. Re-ran `--fix` after the heading hand-fixes: zero diff (stable).
5. `clj-kondo --lint components/release-executor`: 0 errors, 0 warnings,
   including `files.clj`.
6. Ran all 9 test namespaces directly via `clojure -A:dev:test -e
   "(require ...) (clojure.test/run-tests ...)"`: 245 tests, 612
   assertions, 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` newly surfaced on 5 files, all real over-budget files
   the old decorative headings under-reported (none of these 5 reported
   any finding in the baseline):
   - `core.clj` — 4 real layers (max 3)
   - `files.clj` — 5 real layers
   - `metadata.clj` — 6 real layers
   - `sandbox.clj` — 5 real layers
   - `test/pr_body_test.clj` — 4 real layers

   All deferred to Wave 2 (real namespace split), consistent with how
   prior Wave 1 PRs handled the same situation.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only (and one dead test binding removed).
`MINIFORGE_STRATUM_BUDGET_MODE=warn` is required at commit time for the 5
newly-surfaced `SL003` files above.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `core.clj`, `files.clj`,
  `metadata.clj`, `sandbox.clj`, and `test/pr_body_test.clj` (all over the
  3-layer budget)
- Upstream fix: [stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14)
  (`defmethod` refs-union fix), pin bump #1501 — both merged before this
  PR's final commit

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`), at the
      current (post-#1501) stratum-lint pin
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 17 changed files
- [x] Stale `Layer 1.5`/`Layer 0b` banners hand-fixed (3 occurrences),
      re-confirmed stable
- [x] `clj-kondo` clean (0 errors, 0 warnings) across every changed file,
      including `files.clj`
- [x] Component tests pass (245 tests, 612 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except 5 newly-surfaced
      `SL003` files, documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
