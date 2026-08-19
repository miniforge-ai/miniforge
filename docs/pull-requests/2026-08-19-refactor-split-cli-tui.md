<!--
  Title: Split cli/tui.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split tui.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.tui` (bases/cli) into three new sibling
namespaces, resolving a stratum-lint SL003 finding (the combined
namespace measured 4 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, bases/cli
batch. `tui.clj` (508 lines) was flagged directly: `stratum-lint`
reported `SL003 file uses 4 distinct layers (max 3)`.

## Changes in Detail

- New file `tui/terminal.clj` (`ai.miniforge.cli.tui.terminal`): ANSI
  color tables (`ansi-colors`, `ansi-bg-colors`), raw terminal control
  (`clear-screen`, `move-cursor`, `get-terminal-size`, `hide-cursor`,
  `show-cursor`), and small layout helpers (`repeat-char`, `truncate`,
  `style`, `pad-right`). 2 real layers.
- New file `tui/risk.clj` (`ai.miniforge.cli.tui.risk`): PR
  risk/complexity heuristics (`risk-colors`, `risk-icons`,
  `analyze-pr-risk`). 1 real layer.
- New file `tui/interaction.clj` (`ai.miniforge.cli.tui.interaction`):
  nav-tree item view models, the flat-list nav state machine, `gh` CLI
  integration, and raw-mode keyboard input (`render-repo-item`,
  `create-nav-state`, `flatten-nav-items`, `nav-up`, `nav-down`,
  `get-selected-item`, `fetch-pr-details`, `map-char-to-key`,
  `render-pr-list-item`, `update-flat-items`, `fetch-prs-for-repos`,
  `read-key`, `toggle-expand`). 3 real layers.
- `tui.clj`: the 27 defs above are deleted. What's left —
  `render-box`, `render-tree-item`, `render-pr-detail`, and
  `render-two-pane` — now calls `ai.miniforge.cli.tui.terminal` and
  `ai.miniforge.cli.tui.risk` (qualified) instead of same-file defs.
  With those hops no longer counting toward local layer depth, the
  remaining rendering code measures 2 real layers on its own
  (`stratum-lint` exit 0, confirmed directly). The ns docstring is
  updated to describe the new layer shape and the sibling namespaces.
  430 lines removed net, down to 182.

This is pure code motion — no def was added, removed, or behaviorally
altered beyond the call-site qualification the split itself requires.

## Testing Plan

- Repo-wide grep on the fully-qualified namespace
  (`ai\.miniforge\.cli\.tui\b`, across `components`/`bases`/`projects`,
  not a symbol-prefix guess) found **zero** external callers, before
  and after the split — confirmed again on the final state. No test
  file exists for `ai.miniforge.cli.tui` itself (none of the
  `bases/cli/test/ai/miniforge/cli/` fixtures cover it), and no
  `projects/miniforge/test/` caller references it either. Distinct
  from the separate `ai.miniforge.tui-views.*` / `ai.miniforge.tui-engine.*`
  components, which this PR does not touch.
- `stratum-lint` clean (exit 0) on all four touched/new files,
  confirmed directly (not just via the pre-commit autofix).
- `clj-kondo` clean on all four files (0 errors, 0 warnings).
- Direct namespace verification (no test file exists to run, so this
  is a load + assertion smoke check exercising every def moved):
  `clojure -Sdeps '{:paths ["src"] ...cli deps.edn deps...}' -M` a
  script that requires all four namespaces and asserts on
  `terminal/get-terminal-size`, `risk/analyze-pr-risk`,
  `interaction/map-char-to-key`, `interaction/create-nav-state`, and
  `tui/render-two-pane` — `ALL ASSERTIONS PASSED`, `REAL_EXIT_CODE=0`.
- Pre-commit's smoke suite (`bb pre-commit`, cross-component) ran clean
  on every one of the five commits: 345 tests / 1301 assertions, plus
  8 GraalVM compatibility tests / 626 assertions, 0 failures
  throughout.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Precedent: `loader.clj` split, miniforge#1772 (the established
  convention: extract cohesive layer-groups into sibling files under a
  subdirectory named after the original file).
- Precedent: `knowledge_safety.clj` split (2026-08-09) for the PR-doc
  shape this follows.
- Part of the stratum-lint rule-210 remediation program, bases/cli
  batch.

## Notes on commit sequencing

Landing the full split in one commit would have exceeded the 200-line
`commit-budget` ceiling (measured at 307 lines for the three new files
alone, and 360 for the final `tui.clj` rewrite alone). Split into five
commits instead:

1. Add `tui/terminal.clj` + `tui/risk.clj` (176 reportable lines).
2. Add `tui/interaction.clj` (131 reportable lines) — `tui.clj` still
   carried its own copies of the moved defs at this point; nothing in
   the repo called `tui.interaction` yet, so the duplication was inert.
3. Remove the now-duplicated interaction-bound defs from `tui.clj`
   (119 reportable lines). `tui.clj` still measured 4 real layers
   here (the ansi/risk primitives hadn't moved yet) — committed with
   `MINIFORGE_STRATUM_BUDGET_MODE=warn`, the documented escape hatch
   for "a file legitimately mid-way through its own namespace-split
   wave" (`tasks/stratum.clj`'s own wording for the equivalent
   merge-in-progress case).
4. Requalify `render-box`/`render-tree-item` to `tui.terminal`/`tui.risk`
   (38 reportable lines) — `render-pr-detail`/`render-two-pane` still
   used the local ansi/risk defs at this point, so the file kept
   compiling; `MINIFORGE_STRATUM_BUDGET_MODE=warn` used again for the
   same reason. (In practice `stratum-lint --fix`'s autofix regrouped
   the remaining defs by their real dependency graph after this commit
   and the file came out already SL003-clean — confirmed directly —
   ahead of the plan.)
5. Requalify the remaining call sites and delete the now-fully-
   superseded local ansi/risk defs (199 reportable lines, no override
   needed — `stratum-lint` was already clean going in).

Every commit's own `bb pre-commit` run (full smoke + GraalVM suites)
passed; the two mid-split commits are the only ones that needed the
`MINIFORGE_STRATUM_BUDGET_MODE=warn` override, and only for the
already-known SL003 finding this PR exists to fix — not for anything
`--fix` couldn't otherwise resolve.

## Checklist

- [x] Zero unaccounted-for fan-in confirmed via fully-qualified
      namespace grep, before starting and again at the end
- [x] Pure code motion — no logic/behavior changes
- [x] `stratum-lint` clean on every touched/new file at the final
      commit
- [x] `clj-kondo` clean
- [x] Direct namespace load + assertion smoke check passed (no
      existing test file to run instead)
- [x] Pre-commit smoke suite green on every commit (not just the last)
- [x] PR-diff and commit-diff budgets checked (492 insertions / 378
      deletions across 4 files, 199 reportable lines max per commit)
- [x] Adversarial self-review: diffed `tui.clj` end to end against the
      original — every relocated def is byte-identical apart from its
      `:stratum` tag/heading and call-site qualification; no def
      added, removed, or behaviorally altered
