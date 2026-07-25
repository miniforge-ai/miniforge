# fix: stratum-lint pre-commit autofixes instead of just failing

## Overview

Bumps the pinned `stratum-lint` sha to pick up the SL001 lexical-scoping
fix ([miniforge-ai/stratum-lint#6](https://github.com/miniforge-ai/stratum-lint/pull/6)),
and changes `bb lint:stratum` (the pre-commit gate) from "lint staged
files, fail on any finding" to "autofix staged files, re-stage the result,
fail only when autofix genuinely can't resolve it" — the same shape
`bb fmt:md` already uses for Markdown.

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

- `tasks/stratum.clj` (new): the autofix mechanics — `stratum-lint-deps`,
  `restage!`, `lint-only-and-fail!`, `post-fix-lint!`,
  `autofix-and-restage!`. Split out of `tasks/lint.clj` because this PR's
  own dogfooding caught it tripping the very rule it enforces: the real
  call chain (`stratum-lint-deps`/`restage!` → `lint-only-and-fail!`/
  `post-fix-lint!` → `autofix-and-restage!` → the dispatcher) is 4 real
  layers deep, one over budget (rule 210's "a file wanting a fourth band
  is the signal to split the namespace"). `tasks/lint.clj`'s dispatcher
  calls into it via a qualified `stratum/...` reference, which the
  per-file check doesn't count — each file is back to 3 layers, verified
  with the linter itself against both files (zero findings).
  `stratum-lint-deps`'s sha (now in `tasks/stratum.clj`) is bumped to
  `acd82a2f` — the merged lexical-scoping fix,
  [#6](https://github.com/miniforge-ai/stratum-lint/pull/6), plus a
  second fix found while validating this PR end-to-end,
  [#7](https://github.com/miniforge-ai/stratum-lint/pull/7): `--fix` was
  exploding a leading/trailing comment block — e.g. this repo's Apache
  header, on every Clojure file per rule 810 — into one double-spaced
  line per comment instead of one tight block.
- `tasks/lint.clj`:
  - `stratum-staged` now splits staged files by whether they carry
    unstaged changes beyond what's staged (`unstaged-files`, mirroring
    `staged-files`). A partially-staged file (e.g. after `git add -p`) is
    lint-checked with the *old* fail-only behavior instead of autofixed —
    `--fix` reads the working-tree file, so autofixing and re-staging it
    whole would silently include work-in-progress the developer left out
    on purpose (`lint-only-and-fail!`).
  - A fully-staged file goes through `autofix-and-restage!`: run `--fix`,
    then `restage!` (re-`git add`, mirrors `fmt/md-staged`'s re-stage
    step, but checks `git add`'s own exit code — no longer possible for
    the fixed content to fail to stage silently), then `post-fix-lint!`.
    `autofix-and-restage!` only fails the commit when `--fix`'s own exit
    code says it couldn't resolve the file (a parse failure, or a genuine
    same-file reference cycle — SL000/SL007).
  - `post-fix-lint!` runs one more plain (non-fix) lint pass over the
    fixed files and prints any remaining findings — in practice always
    SL003 (over the 3-layer budget), since `--fix` resolves everything
    else, but the message doesn't presume that's the only possibility —
    as a non-blocking advisory. Prints stderr too and fails the commit on
    any exit code other than 0 (clean) or 1 (findings present), so a
    broken tool invocation can't pass silently.
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

Added a fifth path after automated review flagged the working-tree/index
gap: staged one edit to a scratch file (`git add`), then made a second,
unstaged edit to the same file (simulating `git add -p`). Confirmed the
unstaged marker never reached the git index (`git show :file` before and
after `stratum-staged` runs) — the file was lint-checked and the commit
correctly blocked on its pre-existing findings, with zero mutation to the
staged content. Re-ran the plain fully-staged case afterward to confirm
it still autofixes and re-stages normally.

Caught the comment-block bug (#7) exactly this way: this PR's own
pre-commit run autofixed `tasks/lint.clj` itself, and its Apache header —
present in this file and, per rule 810, in every other Clojure source in
the repo — came back double-spaced. Filed and merged the fix upstream,
bumped the pin again, and confirmed re-running the corrected `--fix`
against the already-mangled file self-heals it (verified byte-for-byte:
only the header's blank lines are removed, the already-correct Layer
structure is untouched).

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

- Upstream fixes: [miniforge-ai/stratum-lint#6](https://github.com/miniforge-ai/stratum-lint/pull/6)
  (SL001 scoping), [#7](https://github.com/miniforge-ai/stratum-lint/pull/7)
  (comment-block preservation, found while validating this PR)
- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 0)
- Follow-on: Waves 1-4, per-component fix PRs

**Update (#1471):** the "non-blocking advisory" described above for a
remaining post-fix finding (in practice always SL003) is no longer
current — it now fails the commit by default, same as any other rule 210
violation, with `MINIFORGE_STRATUM_BUDGET_MODE=warn` as an explicit
opt-out. `advisory-lint!` was renamed to `post-fix-lint!` accordingly;
this doc's function-name references above have been updated to match,
but the "non-blocking" framing in the surrounding prose reflects this
PR's original design, not current behavior.

## Checklist

- [x] Both upstream fixes merged before this PR (sequencing per the
      baseline plan)
- [x] All four autofix code paths exercised functionally
- [x] Transitional commit-budget interaction documented, not silently left
      as a surprise
- [x] Comment-block bug found during end-to-end validation, fixed
      upstream, and confirmed self-healing on the already-mangled file
