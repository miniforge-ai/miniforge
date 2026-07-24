# fix: stratum-lint autofix for components/compliance-scanner (Wave 1)

## Overview

Runs `stratum-lint --fix` over the whole `compliance-scanner` component
(`src` + `test`) to replace decorative/misordered `Layer N` headings with
headings that reflect the file's real same-file reference graph, and tags
each `def`/`defn` with `^{:stratum n}` metadata. Purely mechanical: no
logic changes. This is the first of the per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md` found rule 210's per-file
`Layer N` heading convention (`standards/miniforge/languages/clojure.mdc`)
had been cargo-culted across most of the codebase: headings repeated as
visual section breaks rather than one heading per real abstraction
stratum. `compliance-scanner` carried 25 findings under that diagnosis —
21 `SL002` (heading reused instead of strictly increasing), 3 `SL004`
(`def` before the first heading), 1 `SL003` (over the 3-layer budget) —
and zero `SL001` (upward-reference) findings, which is exactly why the
baseline's Wave 1 plan named it a first-batch target: no cycle/upward-call
risk to reason about before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "acd82a2f5c0155cb03d92ce1f4465cc064125895" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/compliance-scanner
```

27 files rewritten (12 `src`, 15 `test`) — every `.clj` file in the
component, including files that had zero prior findings (`--fix`
normalizes those too, adding `^{:stratum n}` metadata as a one-time pass;
matches the tool's documented idempotency behavior). Diff stat:
27 files changed, 1277 insertions(+), 1263 deletions(-).

No line of executable code changed. Verified by stripping `;---- Layer N`
headings, `Rich Comment` headings, and `^{:stratum n}` metadata from both
the pre-fix and post-fix content of every changed file, sorting each set
of lines, and diffing — all 27 files came back byte-identical under that
normalization. The diff itself is def reordering (regrouped under
regenerated headings) plus metadata addition.

A notable finding from running the fixer: only `execute.clj` reported
`SL003` before this fix (4 distinct layers under its old, partially-honest
headings). Once `--fix` inferred each file's *real* stratum count from the
reference graph, 6 more files that looked clean under the old decorative
headings turned out to already be over the 3-layer budget:

| File | Real layers (post-fix) |
|------|------------------------:|
| `classify.clj` | 4 |
| `comments.clj` | 4 |
| `execute.clj` | 5 |
| `named_constants.clj` | 6 |
| `plan.clj` | 5 |
| `scan.clj` | 8 |
| `exceptions_as_data.clj` | 8 |

This confirms the baseline doc's diagnosis for this component specifically:
the decorative headings weren't just mis-ordered, they were actively
under-reporting real structural depth. All 7 need an actual namespace
split (Wave 2), out of scope for this PR.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` over `components/compliance-scanner`
   before the fix — reproduced the baseline's 25 findings exactly (21
   `SL002`, 1 `SL003`, 3 `SL004`, 0 `SL001`).
2. Ran `--fix`, then reviewed `git diff --stat` and read the full diff for
   every changed file (`named_constants.clj`, `execute.clj`,
   `exceptions_as_data.clj`, `comments.clj`, `messages.clj` read in full;
   all others diffed and spot-checked). Confirmed heading regrouping and
   `^{:stratum n}` metadata only — no mangled comment blocks, no altered
   Apache license headers (checked the header's last line is byte-identical
   across all 27 files at the same position), no logic changes.
3. Ran the sort-and-diff content-preservation check described above across
   all 27 changed files programmatically — all reported identical content
   modulo headings/metadata/order.
4. Ran `clj-kondo` over `components/compliance-scanner/src` and `test`
   before and after the fix: 0 errors both times, same single pre-existing
   warning (unresolved `clojure.string` require in `classify_test.clj`, at
   a different line number post-reorder) — confirms no parse/structural
   damage from the rewrite.
5. Re-ran plain `stratum-lint` (no `--fix`) over
   `components/compliance-scanner` after the fix: `SL001`, `SL002`,
   `SL004`, `SL005`, `SL006` all clear. `SL003` remains on 7 files (table
   above) — expected per the baseline's Wave 1/Wave 2 split; each needs a
   real namespace split, tracked as Wave 2 work, not a defect in this PR.
6. Did not run the full `bb pre-commit` / `test:poly` suite manually in
   this exploration — left to the pre-commit hook (`test:precommit`,
   `test:graalvm`) at commit time, which is the real validation gate per
   the baseline plan's Wave 0 decision.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
changes — this is a comment/metadata-only reorder, so there is nothing to
roll out beyond the merge itself. The pre-commit hook's own
`lint:stratum` autofixer (landed in
`2026-07-24-fix-stratum-lint-autofix-precommit.md`) will keep this
component clean going forward for any file it touches; the remaining
`SL003` files stay flagged as advisory (non-blocking) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
- Depends on: `2026-07-24-fix-stratum-lint-autofix-precommit.md` (upstream
  sha bump + autofix wiring)
- Follow-on: Wave 2 namespace splits for `classify.clj`, `comments.clj`,
  `execute.clj`, `named_constants.clj`, `plan.clj`, `scan.clj`,
  `exceptions_as_data.clj` (all now `SL003`, 4–8 real layers each)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Diff reviewed file-by-file; confirmed mechanical-only (heading +
      metadata) via full reads plus a sorted-content-diff check across all
      27 files
- [x] `clj-kondo` clean before/after (0 errors, same pre-existing warning)
- [x] Plain lint re-run post-fix: zero findings except `SL003` (7 files,
      documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
