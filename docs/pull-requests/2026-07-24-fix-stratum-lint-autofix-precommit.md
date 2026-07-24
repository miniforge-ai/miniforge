# fix: stratum-lint pre-commit autofixes instead of just failing

## Overview

Bumps the pinned `stratum-lint` sha to pick up the SL001 lexical-scoping
fix ([miniforge-ai/stratum-lint#6](https://github.com/miniforge-ai/stratum-lint/pull/6)),
and changes `bb lint:stratum` (the pre-commit gate) from "lint staged
files, fail on any finding" to "autofix staged files, re-stage the result,
fail only when autofix genuinely can't resolve it" — the same shape
`fmt:md-staged` already uses for Markdown.

## Motivation

Wave 0 of `work/stratum-lint-baseline-2026-07-24.md`: enforcement was
staged-files-only and fail-only, which is how 876 findings accumulated
across the tree without anyone noticing — a developer touching one line
of an already-messy file got a wall of pre-existing findings unrelated to
their change, with no automated way to clear them. Autofix removes that
friction for the mechanical categories (decorative/misordered headings,
pre-heading defs) so a touched file gets normalized as a side effect of
normal development, not just in dedicated cleanup PRs.

## Changes in Detail

- `tasks/lint.clj`:
  - Bumped `stratum-lint-deps` sha to `d83e92d5` (the merged lexical-
    scoping fix).
  - `stratum-staged` now invokes `--fix` instead of plain lint, re-`git
    add`s every staged file after a successful fix (mirrors
    `fmt/md-staged`'s re-stage step), and only fails the commit when
    `--fix`'s own exit code says it couldn't resolve the file (a parse
    failure, or a genuine same-file reference cycle — SL000/SL007).
  - After a successful autofix, runs one more plain (non-fix) lint pass
    over the same files and prints any remaining findings as a
    non-blocking advisory. In practice this is only ever SL003 (over the
    3-layer budget) — `--fix` regroups defs correctly but can't split a
    file into multiple namespaces, so that stays a manual `rule 210`
    namespace-split, surfaced but not enforced here.
- `bb.edn` — updated `lint:stratum`'s doc string to say "autofix" instead
  of "lint".

## Testing Plan

Functional, in an isolated scratch git repo (not the real tree) exercising
all four paths the new logic can take:

1. A file with decorative/misordered headings — autofixed, re-staged.
2. An already-clean file — still rewritten once, to add `^{:stratum n}`
   metadata (confirmed via the tool's own idempotency guarantee: a second
   fix pass is a byte-for-byte no-op, so this is a one-time normalization
   per file, not a recurring diff).
3. A genuine same-file reference cycle (SL007) — commit correctly blocked,
   file left untouched, exit 1.
4. A file whose real stratum count exceeds the budget after fixing —
   autofixed and re-staged, plus the non-blocking SL003 advisory printed.

Also verified the sha bump itself resolves via `bb -Sdeps` and that
running the pinned linter against the six specific miniforge files from
the baseline (the 5 confirmed false positives + the 1 real violation)
reproduces the fix's effect end-to-end through this repo's own dependency
declaration, not just in the stratum-lint repo's own test suite.

Did not run the full `bb pre-commit` against a real staged production file
in this PR — that's deliberately deferred to the Wave 1-4 per-component
fix PRs, where each component's autofix diff gets reviewed on its own.

## Deployment Plan

Merges to `main`. Takes effect on the next commit that stages a `.clj`/
`.cljc` file. Transitional note: for any of the ~378 files with existing
stratum-lint findings, the *first* commit that touches it after this
merges will carry a full-file reorder/metadata diff as a side effect —
this can be large enough to trip `commit-budget` (200 lines) on an
otherwise-small change. That's an accepted tradeoff for now (override or
land the file's cleanup as its own commit first); Wave 1-4 will have
cleared most of the tree's debt before most developers hit it organically.

## Related Issues/PRs

- Upstream fix: [miniforge-ai/stratum-lint#6](https://github.com/miniforge-ai/stratum-lint/pull/6)
- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 0)
- Follow-on: Waves 1-4, per-component fix PRs

## Checklist

- [x] Upstream fix merged before this PR (sequencing per the baseline plan)
- [x] All four autofix code paths exercised functionally
- [x] Transitional commit-budget interaction documented, not silently left
      as a surprise
