<!--
  Title: Stratum-lint autofix for components/evidence-bundle (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/evidence-bundle (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/evidence-bundle` (`src` +
`test`) to replace decorative `Layer N` headings with real ones
derived from each file's actual same-file reference graph, and tags
every top-level `def`/`defn`/`deftest` with `^{:stratum n}`. One of the
Wave 1 batches from `work/stratum-lint-baseline-2026-07-24.md` (batch
6). Also removes a pre-existing, unrelated data-corruption artifact in
`collector.clj` (see Motivation) and corrects several stale decorative
section labels whose numbers now contradict the real headings/strata
around them.

## Motivation

Baseline plain (non-`--fix`) lint run for this component, before
touching anything, confirmed zero `SL001` (no upward-reference/cycle
risk — this component was not on the pre-vetted SL001-free list, so
this was checked directly rather than assumed): 12 findings, all
`SL002`, 0 `SL001`.

**Pre-existing corruption found and fixed, unrelated to stratum-lint
itself:** `collector.clj` line 163 (on `main` since commit `aa32492b4d`,
2026-04-04 — confirmed via `git blame`) read:

```clojure
   {}))\n\n(defn build-phase-evidence
```

The `\n\n` here is not an escaped newline inside a string — it's two
literal, standalone Clojure character literals (`\n` = the character
`n`, valid but pointless reader syntax) sitting between two real defs
with no actual line break between them in the source. Harmless at
runtime (each evaluates to a character and is discarded), invisible to
`clj-kondo` (valid syntax) and to a plain read-through of the file, but
`--fix` correctly parses these as two unrecognized top-level forms and
(per the same appendix-relocation mechanism already documented for
`stratum-lint`'s SL008 bug and the bare-top-level-call sweep found in
`phase-software-factory`) moves them to the very end of the file,
rendering as two isolated `\n` lines after the last real def — which is
what actually triggered investigation here. Verified this is isolated:
grepped the whole repo for the same "closing bracket directly followed
by literal `\n\n` directly followed by an opening paren" shape — only
this one hit; every other `\n\n` occurrence in the codebase is normal
escaped-newline content inside an actual string literal. Fixed by
restoring a real blank line in place of the two literal `\n` tokens.
Confirmed `--fix` no longer produces any trailing garbage, and is
idempotent (zero diff on a second run) with this one-line fix in place.
**Not a stratum-lint bug** — the tool faithfully preserved and
relocated pre-existing garbage that predates this whole program by
months; no upstream issue filed.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/evidence-bundle
```

All 23 changed `.clj` files (11 src + 11 test, plus the one
hand-edited `collector.clj` line noted above) got real headings +
`^{:stratum n}` metadata. No `SL008` refusal — no reader-conditional-
wrapped def in this component.

Hand-fixed, beyond the raw `--fix` output, all of the same
"decorative-heading-with-a-non-integer-suffix" shape already seen
across prior Wave 1 batches (the heading regex requires a bare integer,
so anything with a letter/decimal suffix survives `--fix` unrecognized
and can end up sitting under a real heading with a contradicting
number):

- `collector.clj`: `Layer 1.5`, `Layer 3.5`, `Layer 4.5` — all three
  sat between the real `Layer 0` and `Layer 1` headings, over content
  tagged `^{:stratum 0}` by the fix. Dropped the wrong numbers, kept
  the descriptive text as plain comments.
- `schema.clj`: `Layer 5a` and `Layer 5b` — both between the real
  `Layer 0`/`Layer 1` headings respectively, over stratum-0/stratum-1
  content. `Layer 0b` — between the real `Layer 3` and `Layer 4`
  headings, over `validate-schema` (`^{:stratum 3}`) — also had a
  companion comment referencing "Layer 5+" by the old cargo-cult
  numbering, reworded since that number no longer means anything
  concrete.
- `interface.clj`: `Layer 7b` — the file has exactly one real heading
  (`Layer 0`, everything in the file collapses to it), so `7b` was
  wildly stale. Dropped, kept the descriptive text.

## Testing Plan

1. Ran plain `stratum-lint` before any change — reproduced the 12
   findings above exactly, confirmed 0 `SL001`.
2. Fixed the pre-existing `\n\n` corruption in `collector.clj` (see
   Motivation) before running `--fix`, so the fixer's output doesn't
   carry it forward.
3. Ran `--fix` over the whole component, then a second `--fix` pass
   immediately after — zero diff both before and after the manual
   decorative-heading cleanup, confirms idempotency.
4. Read the full diff for all 23 changed files. No same-line trailing
   comment displaced onto the wrong def.
5. `clj-kondo --lint components/evidence-bundle`: 0 errors, 0 warnings
   (before and after).
6. Ran all 11 test namespaces directly (`clojure -M:dev:test`, since
   `:poly test` can sweep in an unrelated pre-existing environment
   flake on this machine — `ai.miniforge.pr-lifecycle.monitor-worklist-test`
   throwing on `babashka.fs/delete-tree`): 110 tests, 339 assertions, 0
   failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. Three `SL003` findings remain — `collector.clj` (6 real
   layers), `extraction.clj` (4), `schema.clj` (7) — all genuinely over
   the 3-layer budget, not a regression this PR introduces (the old
   headings undercounted real depth). Deferred to Wave 2 (real
   namespace splits).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change beyond removing two inert, discarded character-literal
expressions — comment/metadata/order-only otherwise. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
the three `SL003` files stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn`
at commit time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `collector.clj` (6 layers),
  `extraction.clj` (4 layers), `schema.clj` (7 layers)
- Investigated as a possible 5th stratum-lint tool bug; concluded not
  a tool bug — root cause is pre-existing source corruption dating to
  commit `aa32492b4d` (2026-04-04), unrelated to this program

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 23 changed files
- [x] Pre-existing unrelated data corruption found, root-caused via
      `git blame`, confirmed isolated (repo-wide grep), and fixed
- [x] `clj-kondo` clean: 0 errors, 0 warnings
- [x] Component tests pass (110 tests, 339 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on 3 files, newly surfaced by the fix, tracked as Wave 2
      above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
