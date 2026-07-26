<!--
  Title: fix: stratum-lint autofix for components/tui-views (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/tui-views (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/tui-views` (`src` + `test`) to
replace decorative/non-monotonic `Layer N` headings with real ones derived
from each file's actual same-file reference graph, and tags every top-level
`def`/`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches from
`work/stratum-lint-baseline-2026-07-24.md` (batch 6). Mechanical: no
executable logic changed. Beyond the autofix output, hand-fixes 51 stale
decorative `Layer N` comments across 15 files that now contradict the
regenerated real headings, and corrects 12 namespace docstrings whose
hardcoded layer count/range the fix invalidated. Also documents one file,
`view/interpret.clj`, that `--fix` correctly declines to touch because it
contains a genuine same-file reference cycle (`SL007`) — a new finding
class not seen in prior Wave 1 batches, flagged for separate triage rather
than worked around here (see Changes in Detail and Related Issues/PRs).

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/tui-views` reported 20
findings, **zero `SL001`** (no upward-reference/cycle risk requiring human
triage before running the mechanical fixer — confirmed via a fresh run
before touching anything, per the batch's mandate to verify this rather
than trust the 2026-07-24 baseline table):

```text
file_subscription.clj:137:1: SL003 file uses 4 distinct layers (max 3)
interface.clj:554:1: SL003 file uses 7 distinct layers (max 3)
model.clj:161:1: SL003 file uses 4 distinct layers (max 3)
schema.clj:95:1: SL002 Layer 0 heading appears after Layer 0
schema.clj:102:1: SL002 Layer 0 heading appears after Layer 0
update/command.clj:450:1: SL002 Layer 4 heading appears after Layer 4
update/command.clj:511:1: SL003 file uses 6 distinct layers (max 3)
update/completion.clj:103:1: SL003 file uses 4 distinct layers (max 3)
update/events.clj:776:1: SL003 file uses 4 distinct layers (max 3)
update/selection.clj:126:1: SL002 Layer 1 heading appears after Layer 1
update/selection.clj:153:1: SL002 Layer 1 heading appears after Layer 1
view/interpret.clj:89:1: SL002 Layer 1 heading appears after Layer 1
view/interpret.clj:243:1: SL003 file uses 4 distinct layers (max 3)
chain_events_test.clj:173:1: SL003 file uses 4 distinct layers (max 3)
command_test.clj: 7 SL004 findings (defs before first Layer heading)
```

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/tui-views
```

73 of the component's `.clj` files were rewritten (41 `src`, 32 `test`) —
`--fix` normalizes every file it touches, not just the ones with findings.
`interface.clj` (`SL003`, 7 layers pre-fix) was **not** rewritten: it
already carried exactly correct, strictly-increasing real `Layer 0`–`Layer 6`
headings and `^{:stratum n}` metadata; `--fix` had nothing to change,
confirming it's a genuinely over-budget file, not a mislabeled one.

**New tool finding: `SL007` reference cycle in `view/interpret.clj`.**
`--fix` exited 1 with:

```text
components/tui-views/src/ai/miniforge/tui_views/view/interpret.clj:1:1: SL007 reference cycle prevents stratification: interpret-box -> interpret-node
```

This is a real, correct diagnosis, not a tool bug. `interpret.clj` is a
recursive-descent view-spec interpreter: `interpret-node` dispatches on
`:type` and calls `interpret-box` (among other node handlers), and
`interpret-box` calls back into `interpret-node` (via a forward `declare`)
to render its child. That's genuine mutual recursion between a dispatcher
and one of its own dispatch targets — not a decorative-heading artifact,
and not resolvable by reordering/relabeling, since no linear `Layer N`
assignment can make both directions monotonic. `--fix` correctly declined
to touch this file at all (confirmed via a fresh clean-tree re-run:
identical result, and a second `--fix` pass over the already-fixed tree:
zero diff for every other file, same `SL007` refusal for this one) rather
than guessing an unsafe reorder. Per the batch runbook's instruction to
stop and report rather than hand-restructure an unfamiliar tool refusal:
this is left completely untouched (byte-identical to `origin/main`,
verified) and out of scope for this PR. It still carries its pre-existing
`SL002`/`SL003` findings post-fix. This wasn't caught by the
2026-07-24 baseline's SL001-only triage because `SL007` is a `--fix`-time
check (it fires when the tool tries to compute real strata, not from the
static heading text plain-lint reads) — worth a note for whoever plans
Wave 3 (see Related Issues/PRs), since other components' baselines were
also only vetted for `SL001`, not `SL007`.

Verified no functional content moved or changed beyond reorder + metadata
across all 73 rewritten files: read the full diff for every file, and
independently confirmed it with a semantic check — parsed both the
`origin/main` and working-tree versions of each file as Clojure forms,
stripped `^{:stratum n}` metadata from every level, and compared the
resulting multisets of top-level forms. Identical for all 73 files (see
Testing Plan). Also grepped the diff for the known same-line
trailing-comment displacement pattern (`foo])  ; comment`) — every match
checked by hand against `origin/main`; all correctly attached, none
displaced.

**Decorative banner cleanup (51 instances, 15 files).** Comments matching
`;---- Layer N` or `;; Layer N: <label>` that predate this tool (invisible
to its heading regex, which requires the line to end right after the
number) now sat inside, or after, a real regenerated heading with a
contradicting or redundant number:

- `transition.clj`, `persistence.clj` (×2), `prompts.clj`,
  `views/workflow_list.clj`: single decorative sub-heading each
  (`Layer 0b`, `Layer 2b`/`2c`, `Layer 0a`, `Layer 0b`) sitting inside a
  real span with a different actual stratum. Dropped the number, kept the
  label as a plain comment.
- `schema.clj` (7), `msg.clj` (5), `update/events.clj` (5),
  `update/command.clj` (4), `view/project/helpers.clj` (6),
  `view/project.clj` (2): same pattern, multiple decorative sub-letters
  (`0b`/`0c`/`0d`/`0e`/`0f`/`0g`/`0a`, `1b`/`1c`/`1d`/`2b`, `5c`/`2b`/`3b`/
  `3c`/`5b`) each contradicting the real heading/stratum now surrounding
  them. `view/project/helpers.clj` also had one wrong-numbered plain
  comment (`;; Layer 0 — recommendation branch helpers...` sitting under
  real `Layer 2` defs) — dropped the number, kept the description.
- Test files: `msg_test.clj` (3), `subscription_test.clj` (2),
  `persistence/pr_cache_test.clj` (6), `view/supervisory_domain_test.clj`
  (5), `ws2_subscription_test.clj` (1) — the `;;----...Layer N: <label>`
  double-semicolon variant, one per `deftest` group, all claiming layers
  the real (regenerated, single-layer-per-file in most cases) structure
  doesn't have.

Re-ran `--fix` after each round of these edits — zero diff every time,
confirming the manual edits don't interact with stratum computation
(comments aren't part of the reference graph).

**Docstring corrections (12 files).** Namespace docstrings that hardcoded
a per-file layer count/range the fix invalidated (same class as the
`repo-dag` Wave 1 batch's precedent — see Related Issues/PRs):
`persistence.clj`, `update.clj`, `update/selection.clj`,
`update/events.clj`, `update/filter.clj`, `update/navigation.clj`,
`update/completion.clj`, `persistence/pr.clj`, `persistence/pr_cache.clj`,
`view/project.clj`, `view/project/trees.clj`, `view/project/helpers.clj`.
Each now states the real layer span (and, where over budget, an explicit
Wave 2 note) instead of a stale single-layer or wrong-range claim. Files
whose docstring already matched the fixed reality (`update/command.clj`,
`update/mode.clj`, `update/chat.clj`, `update/pane.clj`,
`view/project/supervisory.clj`, `views/tab_bar.clj`, and the generic
"Layer 0 — no dependencies on other tui-views namespaces" line shared by
`schema.clj`/`transition.clj`/`msg.clj`/`effect.clj`/`prompts.clj`, which
is still true regardless of how deep each file goes) were left alone.
`view/interpret.clj`'s docstring was deliberately **not** touched, kept
consistent with leaving that file wholly untouched (above).

No `#?(...)` reader-conditional-wrapped defs in this component, so the
`SL008` fix in the current pin never came into play.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the 20 findings above exactly, confirmed 0 `SL001`.
2. Ran `--fix` over the whole component — 73 files rewritten, exit 1 due
   to the `SL007` refusal on `view/interpret.clj` (expected, see above).
   Re-ran from a clean `origin/main` checkout independently — identical
   result, confirming determinism.
3. Ran `--fix` a second time immediately after — zero diff across all 73
   rewritten files, same single `SL007` refusal; confirms idempotency.
4. Read the full diff for all 73 changed files. Verified mechanical-only
   via the normalized-content (parsed-form multiset) check described
   above — passed for all 73 files. Found and hand-fixed the 51 stale
   decorative `Layer N` comments (15 files) and 12 stale docstrings
   described above; re-ran `--fix` after each round — zero diff every
   time, confirming stability.
5. `clj-kondo --lint components/tui-views`: 0 errors, 0 warnings, both
   before and after.
6. Ran all 30 test namespaces directly via `clojure -M:dev:test` (from
   repo root, so the component's own transitive deps resolve through the
   root `:dev`/`:test` classpath): **672 tests, 2283 assertions, 0
   failures, 0 errors**.
7. Re-ran plain `stratum-lint` after the fix (and after all hand edits).
   `SL001`/`SL004` clear. `SL002` remains only on `view/interpret.clj`
   (the untouched `SL007` file, pre-existing, documented above). `SL003`
   remains on 20 files (up from 8 pre-fix — the old headings under-counted
   real depth on most of them once measured from the true reference
   graph): `file_subscription.clj` (5), `interface.clj` (7),
   `persistence.clj` (9), `persistence/github.clj` (4),
   `persistence/pr.clj` (5), `persistence/pr_cache.clj` (4), `update.clj`
   (7), `update/command.clj` (4), `update/events.clj` (4),
   `update/filter.clj` (6), `update/mode.clj` (4), `update/navigation.clj`
   (5), `update/selection.clj` (5), `view/interpret.clj` (4),
   `view/project.clj` (5), `view/project/helpers.clj` (8),
   `view/project/trees.clj` (5), `views/pr_fleet.clj` (4),
   `views/workflow_detail.clj` (4), `views/workflow_list.clj` (5). All
   real over-budget files (Wave 2 scope: namespace split), not addressed
   here.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward for
every file except `view/interpret.clj`, which stays exempt until its
`SL007` cycle is resolved by a real restructure (Wave 2/3 candidate, not
this PR); the 20 `SL003` files stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them. Committed with `MINIFORGE_COMMIT_BUDGET_OVERRIDE=1` per the batch
runbook (large mechanical diff, no logic change).

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Precedent for the decorative-banner hand-fix and stale-docstring
  correction: the `repo-dag` and `web-dashboard` Wave 1 batch PRs
- Follow-on: Wave 2 namespace splits for the 20 `SL003` files listed
  above, `persistence.clj` (9 real layers) being the most over budget
- Follow-on: `view/interpret.clj`'s `SL007` reference cycle
  (`interpret-box` <-> `interpret-node`) needs a real restructure (e.g.
  extracting the recursive dispatch loop so the mutual recursion no
  longer crosses a `Layer N` boundary) — flagging this as a new finding
  class for whoever scopes Wave 3, since other components' Wave 1
  baselines were vetted for `SL001` only, not `SL007`

## Checklist

- [x] Confirmed zero `SL001` before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 73 changed files; verified mechanical-only
      via normalized-content (parsed-form multiset) comparison
- [x] 51 stale decorative `Layer N` comments across 15 files hand-fixed
      (comment-only); `--fix` re-run confirms stability
- [x] 12 stale namespace docstrings corrected to match real layer spans
- [x] New `SL007` reference-cycle finding on `view/interpret.clj`
      investigated, confirmed genuine (not a tool bug), left untouched,
      and flagged for Wave 2/3 follow-up rather than worked around
- [x] `clj-kondo` clean (0 errors, 0 warnings before/after)
- [x] Component tests pass (672 tests, 2283 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL004`; `SL002` remains
      only on the untouched `SL007` file; `SL003` remains on 20 files,
      documented above with precise counts, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
