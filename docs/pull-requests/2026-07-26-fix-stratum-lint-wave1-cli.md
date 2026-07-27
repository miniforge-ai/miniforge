<!--
  Title: fix: stratum-lint autofix for bases/cli (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

## Overview

**Update after initial review:** the base commit (`3fa5cc503`) is the
mechanical `stratum-lint --fix` pass described below and does carry no
logic changes. Two follow-up commits were added afterward in direct
response to genuine bugs Copilot's review found in files this pass
already touches — a `ClassCastException` in `web/sse.clj` and an
unhandled-exception-on-malformed-input bug in `web/handlers.clj`, both
pre-existing and unrelated to the stratum-lint reordering itself. See
"Post-Review Fixes" below for the full detail on both; flagged here
per review feedback so this description doesn't undersell the PR's
actual scope.

Runs `stratum-lint --fix` over `bases/cli` (`src` + `test`) to replace
decorative `Layer N` banners and missing headings with real `Layer N`
headings and `^{:stratum n}` metadata derived from each file's actual
same-file reference graph. The autofix pass itself is mechanical: no
logic changes. Also hand-fixes 26 stale decorative `Layer N` comments
across 11 files that are now invisible to `--fix`'s heading regex and
contradict the recomputed real headings, and one stale docstring
(`workflow_runner/gc_hooks.clj`) whose "Layer 0 / Layer 1" description
no longer matched the real (single-layer) structure. One of the Wave 1
batch 6 per-component/base PRs from
`work/stratum-lint-baseline-2026-07-24.md`; `bases/cli` is a Polylith
BASE, not a component, but the same fix mechanics apply.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `bases/cli` reported 28 findings,
**zero `SL001`** (confirmed before running `--fix`, per the Wave 1 runbook
— this base was not pre-vetted as SL001-free, so this was a hard
precondition, not a formality):

```text
10 SL002 (heading reused / not strictly increasing)
10 SL003 (file over the 3-layer budget, measured from decorative headings)
 8 SL004 (def appears before the first Layer heading)
```

Matches the baseline doc's per-component table exactly (`bases/cli | 28 |
0 | 10 | 10 | 8`).

## Changes in Detail

Ran, over the whole base:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix bases/cli
```

96 files were rewritten (57 `src`, 39 `test`). No SL008 reader-conditional
refusal encountered.

**Idempotency and content verification.** A second `--fix` pass
immediately after produced zero diff. For all 96 files, stripped
comments/blank lines/`^{:stratum n}` annotations from both the pre-fix and
post-fix text and diffed the sorted, normalized result: identical for 92
of 96 files. The remaining 4 are accounted for below (3 cosmetic, 1
intentional docstring fix) — no unaccounted content drift. Also grepped
the diff for the known same-line trailing-comment displacement pattern
(`foo])  ; comment`): 2 hits, both verified by direct inspection to remain
attached to their original def, only the space count before `;` changed
(1 space → 2) — not a misattachment.

**Decorative banner cleanup (26 instances, 11 files).** Single- and
double-semicolon `;---- Layer N[.: ]<label>` comments left behind by
earlier manual edits — invisible to `--fix`'s heading regex — now
contradicted the regenerated real headings (checked systematically: every
`Layer N`-shaped comment in the diff was traced against the real heading
immediately governing its position, i.e. the last strictly-formatted
bare `;---- Layer N` line above it):

- `observability.clj`: 7 contradicting banners (e.g. `Layer 1: Log
  Parsing` sitting in the real Layer 0 span, `Layer 7: CLI Commands`
  sitting in the real Layer 5 span). Dropped the wrong numbers, kept each
  label as a plain `;; <label>` comment — matches the exact convention
  established in the `web-dashboard` Wave 1 PR (#1525 follow-up,
  `27357297d`). Two correctly-numbered banners (`Layer 0: File
  Discovery`, `Layer 2: Event Parsing`) already matched their real
  stratum and were left alone, per the same precedent.
- `workflow_runner.clj`: 6 instances — four (`Layer 0.5`–`Layer 0.8`) were
  bare non-integer stray headings with no unique text (the real label
  was already on the very next line as a separate plain comment), deleted
  outright; one (`Layer 1 — manifest operations`, real stratum 0) and one
  (`Layer 2 — lifecycle helpers`, real stratum 1) had wrong numbers
  dropped, label kept. Also fixed a stale in-prose reference next to the
  latter (`` `run-workflow!` composes them at Layer 3 `` → real stratum is
  2, corrected the number in the sentence itself). A stray `Layer 2b`
  banner (leftover from an old numbering scheme, no relation to the
  current 0–9 real structure) sitting inside the real Layer 9 span was
  also deleted — no unique text beyond the wrong label.
- `main/commands/resume.clj`: one stray `Layer 1.5` banner, no unique
  text (label already present on the next line), deleted.
- 6 test files (`main/commands/artifact_cmds_test.clj`,
  `main/commands/etl_test.clj`, `main/commands/events_test.clj` (2),
  `workflow_runner/display_output_test.clj`,
  `workflow_runner/help_test.clj` (4), `workflow_selector_test.clj` (2)):
  same shape — one decorative `Layer N: <label>` banner per `deftest`
  group, each now contradicting the real (collapsed) stratum. Dropped
  wrong numbers where a distinct label existed; deleted outright where
  the very next line already carried the same description.

Re-ran `--fix` after all of the above — zero diff, confirming the
manual comment/docstring edits don't interact with stratum computation.

**False positive caught before editing:** `workflow_runner/sandbox.clj:76`
(`;; Layer 0 (\`infer-branch\`).`) was initially flagged by a triage
script as contradicting its surrounding real-Layer-2 context, but reading
it in place showed it's a **correct** cross-reference — it documents that
`prepare-sandbox` (real stratum 2) composes `infer-repo-url` (real
stratum 1) and `infer-branch` (real stratum 0), both true facts about
those functions' own strata, not a claim about the comment's own section.
Left unchanged. `sandbox.clj`'s ns docstring "Stratification" block
(Layer 0–3) is fully accurate post-fix and was also left alone.

**Genuine stale docstring fixed:** `workflow_runner/gc_hooks.clj`'s ns
docstring described the file as two layers (`Layer 0: enqueue...` /
`Layer 1: GC pass...`), but `--fix` collapsed the real structure to a
single Layer 0 — both functions take their side-effecting collaborators
as injected arguments and don't call each other. Reworded the docstring
to describe them as Layer-0 peers instead of a two-layer split.

**Three other files' ns docstrings carry a "Stratification" description
that is now stale** (`workflow_selector.clj`: documents 3 layers, real is
5; `workflow_runner/help.clj`: documents 3, real is 6;
`workflow_runner/help/registry.clj`: documents 3, real is 6) — but all
three files remain over the 3-layer budget after `--fix` (SL003, see
below) and are Wave 2 (namespace split) candidates per the baseline doc.
Left their docstrings as-is rather than hand-writing a description of a
structure that Wave 2 will restructure again; flagged here for whoever
picks up the split.

## Post-Review Fixes

Two genuine, pre-existing bugs Copilot's review found in files the
mechanical pass already touches — both verified independently before
fixing, both untouched by the autofix itself (confirmed via `git diff
origin/main` on each file: only heading/metadata/reordering churn
before these commits, logic byte-identical), both landed as their own
commit with a dedicated regression test:

1. **`web/sse.clj` (`0813e3716`, then `0fb24f09b`):** `streams` maps
   `workflow-id -> event-stream atom` (`es/create-event-stream` returns
   `(atom {...})`, confirmed via `components/event-stream`). `on-open`
   and `on-close` were doing `assoc-in`/`update-in` directly on a
   `streams` entry to track per-channel subscriber ids — this tries to
   `assoc` onto that atom and throws `ClassCastException: class
   clojure.lang.Atom cannot be cast to class clojure.lang.Associative`
   on the first SSE connect for any workflow. Fixed by tracking
   channel→sub-id in a separate `subscriptions` atom instead. A
   follow-up review comment also caught that `on-close` left an empty
   `{workflow-id {}}` entry behind once the last channel closed
   (unbounded growth on a long-running server) — fixed by dropping the
   `workflow-id` key entirely once its channel map goes empty. Added
   `bases/cli/test/ai/miniforge/cli/web/sse_test.clj` (6 tests).
   Reproduced both bugs by reverting the fix locally and re-running the
   new tests against the original logic before restoring it — both
   throw/leak exactly as described.
2. **`web/handlers.clj` (`101bc5a0c`):** `parse-pr-path` unconditionally
   ran `Integer/parseInt` on the second path segment of an
   `/api/pr/<repo>/<number>` URI with no validation, so a malformed
   request (`GET /api/pr/`, a non-numeric PR number, etc.) threw
   `NullPointerException`/`NumberFormatException` straight out of
   request handling instead of a 400 — this is externally reachable,
   untrusted input. Fixed by wrapping the parse in `try`/`catch` (nil on
   failure, matching the existing `workflow-stream` pattern already in
   the same file) and updating all 5 callers (`pr-detail`, `approve`,
   `reject`, `chat`, `summary`) to return `response/bad-request` instead
   of proceeding with a nil repo/number. Added
   `bases/cli/test/ai/miniforge/cli/web/handlers_test.clj` (4 tests).
   Same revert-and-reproduce verification as above.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the 28 findings
   above exactly (10 `SL002`, 10 `SL003`, 8 `SL004`), zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 96 changed files; confirmed via the
   normalized-content multiset check (above) that nothing beyond
   heading/metadata/order/the one intentional docstring edit changed.
   Found and hand-fixed the 26 stale decorative `Layer N` comments across
   11 files described above; re-ran `--fix` after each round of manual
   edits — zero diff, confirming stability.
4. `clj-kondo --lint bases/cli`: 0 errors, 2 warnings (`Redundant let
   expression` in `workflow_runner/gc_integration_test.clj`, unused
   `testing` refer in `worktree_test.clj`). Confirmed pre-existing via
   `git show origin/main:<path>` comparison on the unmodified tree — same
   2 warnings (different line numbers only, from reordering), nothing
   else.
5. Ran the base's 42 test namespaces directly via `clojure -M:dev:test
   -e` (both `src` and `test`, requiring every namespace under
   `bases/cli/test` — including the two new namespaces added by the
   Post-Review Fixes above, `web/sse_test.clj` and
   `web/handlers_test.clj` — and calling `clojure.test/run-tests` on all
   of them): **312 tests, 863 assertions, 1 failure, 1 error** — both
   confirmed pre-existing and unrelated to this diff by running the same
   two test namespaces against the unmodified `origin/main` tree
   (identical failure/error, same messages):
   - `ai.miniforge.cli.scan-test` `negative-mode-uses-rule-title-test`:
     `NullPointerException` inside `ai.miniforge.compliance-scanner.scan`
     (a different component entirely, not touched by this diff).
   - `ai.miniforge.cli.workflow-runner.preflight-test`
     `run-backend-preflight-exercises-generic-cli-success-path-test`:
     depends on which CLI backend binary is actually on this machine's
     `PATH` (`claude` here); fails the same way on `origin/main`.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on 37 files (up from 10 pre-fix — the old
   decorative headings under-counted real depth on most of them once
   measured from the true reference graph, the same effect documented in
   the `web-dashboard` Wave 1 PR): `app_config.clj` (6), `backends.clj`
   (7), `config.clj` (4), `main.clj` (8), `artifact_cmds.clj` (4),
   `etl.clj` (4), `events.clj` (4), `evidence.clj` (5), `init.clj` (4),
   `plan_executor.clj` (4), `pr_monitor.clj` (4), `pr_policy_respond.clj`
   (4), `pr_resume_dispatcher.clj` (5), `pr_review.clj` (5),
   `pr_review_monitor.clj` (4), `resume.clj` (4), `runtime.clj` (4),
   `scan.clj` (5), `main/commands/worktree.clj` (4), `main/display.clj`
   (5), `messages.clj` (4), `observability.clj` (7), `tui.clj` (4),
   `web/components.clj` (7), `web/components/status.clj` (4), `web/sse.clj`
   (4), `workflow_recommender.clj` (5), `workflow_runner.clj` (10),
   `workflow_runner/context.clj` (4), `workflow_runner/display.clj` (8),
   `workflow_runner/help.clj` (6), `workflow_runner/help/registry.clj`
   (6), `workflow_runner/sandbox.clj` (4), `workflow_selection_config.clj`
   (4), `workflow_selector.clj` (5), top-level `worktree.clj` (5),
   `test/web/components_test.clj` (4). All real over-budget files (Wave 2
   scope: namespace split), not addressed here.

Committed with `MINIFORGE_STRATUM_BUDGET_MODE=warn` (37 pre-existing
over-budget files remain, per Wave 1's documented handling of SL003
carry-over) alongside `MINIFORGE_COMMIT_BUDGET_OVERRIDE=1`.

## Deployment Plan

Merges to `main` like any other base change. The stratum-lint autofix
itself is purely heading/metadata/comment/one-docstring-sentence churn,
no runtime behavior change. The two Post-Review Fixes above do change
runtime behavior, both strictly toward correctness: SSE subscribe/close
no longer throws on the first connect for a workflow, and a malformed
`/api/pr/...` request now returns 400 instead of crashing the handler —
no existing caller relied on either failure mode. Pre-commit's
`lint:stratum` autofixer keeps this base clean going forward; the 37
`SL003` files stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at
commit time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for the 37 `SL003` files listed
  above, `workflow_runner.clj` (10 real layers) and `main.clj` (8) being
  the most over budget. `workflow_selector.clj`,
  `workflow_runner/help.clj`, and `workflow_runner/help/registry.clj`
  additionally carry a stale ns-docstring "Stratification" description
  (documents 3 layers, real is 5–6) that should get rewritten as part of
  their split rather than patched piecemeal beforehand.

## Checklist

- [x] Confirmed zero `SL001` before running `--fix`
- [x] `--fix` run over the whole base (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 96 changed files; verified mechanical-only
      via normalized-content comparison
- [x] 26 stale decorative `Layer N` comments across 11 files hand-fixed
      (comment-only); one stale docstring description hand-fixed
      (`gc_hooks.clj`); `--fix` re-run confirms stability
- [x] One triage false-positive (`sandbox.clj:76`) checked against the
      actual reference graph and correctly left unchanged
- [x] `clj-kondo` clean (0 errors, 2 pre-existing warnings, confirmed via
      comparison against `origin/main`)
- [x] Base tests pass (312 tests, 863 assertions, 1 pre-existing failure
      + 1 pre-existing error, both confirmed unrelated on `origin/main`)
- [x] Plain lint re-run post-fix: `SL003` remains on 37 files, documented
      above with precise counts, tracked as Wave 2
- [x] Two genuine bugs found in Copilot review (`web/sse.clj`
      `ClassCastException` + unbounded subscription-map growth,
      `web/handlers.clj` unhandled exception on malformed input) fixed
      with dedicated regression tests; each reproduced against the
      original logic before the fix was restored
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
