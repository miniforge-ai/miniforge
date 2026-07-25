# fix: stratum-lint autofix for components/gate (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/gate` (src + test) and commits the
result: regenerated `;---- Layer N` headings and `^{:stratum n}` metadata on
every top-level def, computed from each file's real same-file reference
graph. No logic changed — verified below. This is one component-scoped PR in
the Wave 1 batch described in `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md`'s full-tree audit found rule 210
(`standards/miniforge/languages/clojure.mdc`) — real one-way-DAG `Layer N`
headings, max 3 per file — had been cargo-culted into decorative section
banners across most of the tree: headings repeated as visual breaks rather
than tracking an actual dependency order. `gate` carried 9 findings (8 SL002
heading-order + 1 SL003 over-budget) and, critically, **zero SL001
upward-reference findings** — nothing in the component calls from a lower
layer up into a higher one — which is exactly the baseline's criterion for
"safe to autofix first, no cycle/reasoning risk." `gate` is one of the five
components named in the baseline's Wave 1 batch (`compliance-scanner`,
`reliability`, `gate`, `decision`, `adapter-claude-code`), chosen to build
confidence before the larger components.

## Changes in Detail

`stratum-lint --fix` (pinned sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e` —
the fully-fixed tool: SL001 scoping, comment-block preservation,
reader-discard attachment, defrecord-constructor ordering, and same-line
trailing comments, all idempotent) rewrote all 24 Clojure files in the
component — every file, not just the 9 that had findings, because `--fix`
always regenerates canonical headings and tags every def with
`^{:stratum n}`, even in an already-heading-compliant file.

This branch went through several redo cycles before this final version,
each catching a real, separate issue — recorded for anyone reading the
history:

1. First pass (sha `acd82a2f`) — the baseline autofix.
2. Automated review caught `policy.clj`'s `; GitHub tokens` comment
   (documenting the last regex in `secret-patterns`) relocated above the
   unrelated `check-no-secrets` — [stratum-lint#9](https://github.com/miniforge-ai/stratum-lint/issues/9),
   fixed upstream in [#12](https://github.com/miniforge-ai/stratum-lint/pull/12).
3. Re-verifying #12 found it wasn't idempotent — a second `--fix` pass
   silently re-migrated the same comment, reproducing #9 exactly — fixed
   in [#13](https://github.com/miniforge-ai/stratum-lint/pull/13).
4. This branch's own `tasks/stratum.clj` had never been rebased onto
   `main` through any of the above pin bumps, so every redo attempt was
   silently re-broken by the pre-commit hook running `--fix` a second
   time with the branch's stale, still-buggy local pin — regardless of
   what the working tree looked like right before committing. Reset the
   branch onto current `main` (pin `80699e37`, matching what's actually
   enforced) before this final redo, so the hook's own re-verification
   uses the same fixed tool instead of fighting it.

`policy.clj`'s comment now sits on the *same line* as `secret-patterns`'s
closing `])` — verified stable across two consecutive `--fix` passes
before committing, not just trusted after one.

Since the last redo attempt on this branch, [#1471](https://github.com/miniforge-ai/miniforge/pull/1471)
made any remaining post-fix finding (SL003 here — 5 files over the
3-layer budget, unchanged from the baseline) block the commit by default
instead of print a non-blocking advisory. This commit uses
`MINIFORGE_STRATUM_BUDGET_MODE=warn`: these 5 files are pre-existing,
already-tracked violations this PR doesn't create or worsen — the
mechanical fix pass can't resolve them (that needs an actual namespace
split, Wave 2, not attempted here) — and blocking a comment-attachment
correction on a pre-existing, already-documented violation isn't the
scenario the override exists to prevent.

- **src (13 files):** `behavioral.clj`, `capabilities.clj`, `format.clj`,
  `interface.clj`, `lint.clj`, `messages.clj`, `policy.clj`,
  `policy_pack.clj`, `pre_verify_lint.clj`, `precommit_discipline.clj`,
  `registry.clj`, `syntax.clj`, `test.clj`
- **test (11 files):** `behavioral_test.clj`, `capabilities_test.clj`,
  `format_test.clj`, `interface_test.clj`, `lint_test.clj`,
  `policy_pack_test.clj`, `policy_test.clj`, `pre_verify_lint_test.clj`,
  `precommit_discipline_test.clj`, `registry_test.clj`,
  `validation_pipeline_test.clj`

## Testing Plan

- `--fix` run twice in a row before committing; zero diff between the
  two passes (idempotency confirmed directly, not assumed).
- `clj-kondo --lint components/gate`: 0 errors, 0 warnings.
- Plain (non-fix) lint after fixing: 5 SL003 findings remain
  (`policy_pack.clj` 9 layers, `format.clj` 8, `capabilities.clj` 7,
  `pre_verify_lint.clj` 5, `precommit_discipline.clj` 4) — genuine,
  reference-graph-derived depth exceeding the budget, Wave 2 scope, not
  a regression from this change.
- Full `bb pre-commit` gate green: `poly:check`, `lint:clj`,
  `lint:stratum` (advisory under `MINIFORGE_STRATUM_BUDGET_MODE=warn`
  for the reason above), `fmt:md`, `test:precommit` (331 tests),
  `test:graalvm` (7 tests).

## Deployment Plan

Merges to `main`. No behavior change to `gate` itself — headings,
metadata, and comment positions only.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Upstream fixes consumed: [stratum-lint#12](https://github.com/miniforge-ai/stratum-lint/pull/12), [#13](https://github.com/miniforge-ai/stratum-lint/pull/13)
- Blocking-gate change this PR uses the override for: [#1471](https://github.com/miniforge-ai/miniforge/pull/1471)
- Follow-on: Wave 2 namespace splits for the 5 SL003 files above

## Checklist

- [x] Idempotency verified directly (two `--fix` passes, zero diff)
      before committing, not just after one pass
- [x] `clj-kondo` clean across the whole component
- [x] SL003 remainder unchanged from every prior pass on this branch —
      documented as Wave 2 scope, not attempted here
- [x] Commit-budget and stratum-budget overrides both used with
      recorded rationale, no hooks skipped
