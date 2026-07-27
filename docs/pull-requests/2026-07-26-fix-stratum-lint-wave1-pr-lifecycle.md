<!--
  Title: fix: stratum-lint autofix for components/pr-lifecycle (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/pr-lifecycle (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/pr-lifecycle` (`src` + `test`)
to replace decorative `Layer N` banners and missing headings with real
`Layer N` headings and `^{:stratum n}` metadata derived from each file's
actual same-file reference graph. Mechanical: no logic changes. Also
hand-fixes 15 stale decorative heading/comment artifacts across 4 files
that now contradict the recomputed real headings, and removes 2
now-dead `declare` forms left over once reordering placed their forward-
declared def earlier in the file than the declaration itself. One of the
Wave 1 batch 6 per-component PRs from
`work/stratum-lint-baseline-2026-07-24.md` — the largest component in
this batch (38 pre-fix findings).

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/pr-lifecycle` reported
38 findings, **zero `SL001`**:

```text
30 SL002 (Layer heading reused/non-monotonic)
 6 SL003 (file over the 3-layer budget)
 2 SL004 (def before first Layer heading)
```

Zero `SL001`, confirming this component carries no upward-reference/cycle
risk requiring human triage before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/pr-lifecycle
```

61 of the component's `.clj` files were rewritten (31 `src`, 30 `test`).
One file, `src/.../monitor_loop.clj`, was **not** rewritten and reported
`SL007 reference cycle prevents stratification: continue-loop! ->
step-monitor-loop!` — a genuine same-file mutual recursion between the
loop's two step functions (`continue-loop!` calls `step-monitor-loop!`
at the end of a sleep/retry branch; `step-monitor-loop!` calls
`continue-loop!` at the end of its own success branch). This is not a
new tool bug: `tasks/stratum.clj`'s own docstring for
`autofix-and-restage!` documents exit 1 as reserved for exactly this
case ("a parse failure, or a same-file reference cycle"). The file
wasn't in the original 38-finding list (it already lint-passed
pre-fix) and is untouched by this diff — confirmed via `git diff
--stat` showing no entry for it.

Verified no functional content moved or changed beyond reorder +
metadata, two ways:

1. Stripped comments/blank lines/`^{:stratum n}` annotations from both
   the pre-fix and post-fix text of all 61 files and diffed — non-empty
   only for cosmetic double-space artifacts from the stripping itself
   (metadata removal leaves a doubled space around `def`/`defn` heads).
2. Stronger check: read every top-level `def`/`defn`/`defn-`/
   `defrecord`/`defmethod`/`deftest` form from both versions of each
   file with the Clojure reader, stripped `:stratum` from its metadata,
   `pr-str`'d it, and compared the sorted multiset of (head+name,
   canonical-form) pairs old vs. new per file — **identical across all
   61 files**. This confirms every def's name, arglist, docstring, and
   body is byte-for-byte the same before and after; only physical
   position and heading/metadata changed. Also confirmed `(:require
   ...)` clauses are untouched in every file's `ns` form, and that the
   only non-`def` top-level forms in the component (`(comment ...)`
   scratch blocks, `(declare ...)`) were left in place — no bare
   side-effecting top-level call exists in this component, so the
   known "appendix sweep reorders a call relative to its dependents"
   failure mode doesn't apply here.

**Decorative banner cleanup (15 instances, 4 files).** Orphaned
`Layer N` artifacts, invisible to `--fix`'s heading regex (which only
recognizes a bare `;---- Layer <int>` line), left behind once real
integer headings were regenerated around them:

- `ci_monitor.clj`: one fractional `;---- Layer 0.5` banner (no label
  of its own) sitting inside what `--fix` computed as a single real
  `Layer 0` span. Dropped, kept the plain `;; Value coercion` comment
  already on the next line.
- `github.clj`: same shape, one fractional `;---- Layer 1.5` banner
  inside the real `Layer 0` span. Dropped, kept `;; Batched review
  posting (N13 §2.2 Standards Reviewer)`.
- `interface.clj`: six fractional banners (`Layer 2.5`, `2.7`, `2.8`,
  `2.9`, `1.5`, `5.5`) — leftovers from before `--fix` collapsed this
  namespace's real depth to a single `Layer 0` (an interface file
  mostly re-exports other namespaces' functions by reference, so its
  same-file reference graph is flat; this fully resolves the file's
  original `SL003` — 8 layers pre-fix, 0 extra layers post-fix). All
  six had a plain descriptive comment immediately following; dropped
  the fractional banner, kept the label.
- `listener_registry.clj`: two short unicode-dash inline comments
  (not matching the tool's banner regex at all) with stale
  parentheticals — `;; ── Entry construction (Layer 1; consumes only
  Layer 0/1) ───────────` sitting next to a real-stratum-0 def, and
  `;; Lifecycle entry points (orchestrate Layer 1/2 ops; do I/O)`
  sitting directly under the real `Layer 9` heading. Both predate this
  file's real depth turning out to be far greater than its old
  cargo-cult headings suggested (see `SL003` note below); dropped the
  now-wrong layer references, kept the descriptive text.
- `test/monitor_worklist_test.clj`: five full-width `;---- Layer N:
  <label>` banners (`Layer 1: worklist-path`, `Layer 1: repo-key`,
  `Layer 2: persist-worklist!`, `Layer 2: load-worklist`, `Layer 2:
  prune-closed-prs`) — one per `deftest` group, the classic
  per-function-group banner pattern from the baseline diagnosis.
  Converted each to a plain `;; <label>` comment.

Re-ran `--fix` after these edits — zero diff, confirming the manual
edits didn't interact with stratum computation (comments aren't part of
the reference graph).

**Dead-code cleanup found via `clj-kondo` (2 files).** Reordering
surfaced two `declare` forms that are now genuinely redundant because
the def they forward-declare landed earlier in the file than the
declaration itself once physically reordered to its real stratum:
`fsm.clj`'s `(declare transition-failure)` (real def already at
`Layer 1`, well before this trailing declare) and `monitor_state.clj`'s
`(declare load-budget-from-disk!)` (real def already earlier in the
file; this was also the file's last line, trailing a now-orphaned `;;
Shared helpers` comment). `clj-kondo` flagged both as "Redundant
declare" — new warnings not present pre-fix. Removed both declares
(and the orphaned comment in `monitor_state.clj`); re-ran `clj-kondo`
and confirmed the warning count returned to the pre-existing baseline
exactly. Re-ran `--fix` again after — zero diff.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the 38 findings
   above exactly (30 `SL002`, 6 `SL003`, 2 `SL004`), zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero
   rewrites (only the `monitor_loop.clj` `SL007` diagnostic, unchanged),
   confirming idempotency.
3. Read the full diff for all 61 changed files; used the def-body
   multiset comparison described above to confirm nothing beyond
   heading/metadata/order changed, then found and hand-fixed the 15
   stale decorative heading/comment artifacts and the 2 dead `declare`
   forms described above. Re-ran `--fix` after each round of manual
   edits — zero diff both times.
4. `clj-kondo --lint components/pr-lifecycle`: 0 errors, 2 warnings
   (redundant `let` in `test/monitor_worklist_test.clj`) + 2 info
   (redundant `ignore` in `test/integration_test.clj`). Confirmed
   pre-existing via `git stash` + re-lint on the unmodified tree — same
   2 warnings + 2 info (different line numbers, since content moved),
   nothing else.
5. Ran the component's test namespaces directly via `clojure -M:dev:test
   -e` (30 namespaces — every `test/` namespace including the 5
   `anomaly/` namespaces, from repo root): **422 tests, 2410 assertions,
   0 failures, 0 errors.** This includes
   `ai.miniforge.pr-lifecycle.monitor-worklist-test`, which per the Wave
   1 batch 6 briefing has a known pre-existing environment flake
   (`babashka.fs/delete-tree` throwing `ClassCastException` in its
   cleanup fixture) — it did not reproduce across 3 separate runs (once
   in isolation twice, once as part of the full 30-namespace suite);
   all runs passed cleanly.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on 21 files (up from 6 pre-fix — most of the
   old headings under-counted real depth once measured from the true
   reference graph):
   `ci_monitor.clj` (4), `classifier.clj` (6), `conflict_resolution.clj`
   (4), `controller.clj` (7), `controller_config.clj` (5), `fsm.clj`
   (10), `github.clj` (5), `listener_registry.clj` (11), `merge.clj`
   (4), `monitor_budget.clj` (4), `monitor_config.clj` (6),
   `monitor_worklist.clj` (4), `policy_eval/fs.clj` (4),
   `policy_eval/reply.clj` (4), `pr_poller.clj` (5), `responder.clj`
   (4), `resume_dispatcher.clj` (10), `review_monitor.clj` (4),
   `test/ci_monitor_property_test.clj` (4),
   `test/monitor_loop_test.clj` (4), `test/resume_dispatcher_test.clj`
   (4). All real over-budget files (Wave 2 scope: namespace split), not
   addressed here. Notably `interface.clj` (originally 8 layers) and
   `triage.clj` (originally 4) both fully resolved to within budget once
   their real (shallower) depth was measured — the opposite direction
   from `listener_registry.clj`, whose real depth (11) turned out
   larger than its pre-fix headings implied (4).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — every def's body is unchanged (verified above); the only
non-cosmetic edits are removing 2 dead `declare` forms, which are
no-ops once their target is already defined. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; the
21 `SL003` files stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at
commit time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for the 21 `SL003` files listed
  above, `listener_registry.clj` (11 real layers) and `fsm.clj` /
  `resume_dispatcher.clj` (10 each) being the most over budget.

## Checklist

- [x] Confirmed zero `SL001` before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 61 changed files; verified mechanical-only
      via def-body multiset comparison
- [x] 15 stale decorative `Layer N` heading/comment artifacts across 4
      files hand-fixed (comment-only); `--fix` re-run confirms stability
- [x] 2 dead `declare` forms (surfaced by `clj-kondo`, caused by
      reordering) removed
- [x] `clj-kondo` clean (0 errors, 2 warnings + 2 info, confirmed
      pre-existing via `git stash`)
- [x] Component tests pass (422 tests, 2410 assertions, 0
      failures/errors); known `monitor-worklist-test` flake did not
      reproduce
- [x] Plain lint re-run post-fix: `SL003` remains on 21 files, documented
      above with precise counts, tracked as Wave 2
- [x] `monitor_loop.clj`'s `SL007` reference-cycle diagnostic
      investigated and confirmed pre-existing/expected, file untouched
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
