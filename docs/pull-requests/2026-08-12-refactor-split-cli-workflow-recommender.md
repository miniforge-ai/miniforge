<!--
  Title: Split cli/workflow_recommender.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split workflow_recommender.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.workflow-recommender` (227 lines) into three
files, resolving a stratum-lint SL003 finding (the combined namespace
measured 5 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
batch (task #31). `workflow_recommender.clj` mixed prompt construction,
LLM invocation/parsing, and recommendation orchestration in one file,
producing a 5-deep same-namespace call chain.

## Changes in Detail

- New file `workflow_recommender/prompt.clj`
  (`ai.miniforge.cli.workflow-recommender.prompt`): `build-workflow-
  summary`, `extract-spec-summary` (layer 0), `build-workflow-
  summaries` (layer 1), `build-recommendation-prompt` (layer 2) — 3
  layers, within budget.
- New file `workflow_recommender/llm.clj`
  (`ai.miniforge.cli.workflow-recommender.llm`): `parse-llm-response`,
  `call-llm-for-recommendation` — 1 layer.
- `workflow_recommender.clj`: `recommend-by-task-type` and
  `recommend-workflow` now reference only sibling-namespace functions
  (`prompt/build-recommendation-prompt`, `llm/call-llm-for-
  recommendation`, `llm/parse-llm-response`) rather than same-file
  symbols, so both sit at layer 0; only `recommend-workflow-with-
  fallback` (depends on both, locally) is layer 1. 2 layers total,
  down from 5.
- One cosmetic local-var rename inside `recommend-workflow`: the
  `prompt` let-binding became `prompt-text`, to avoid shadowing the
  new `prompt` namespace alias required in the same file. No behavior
  change — same value, same call sites, new name only.
- `workflow_recommender_test.clj`: `build-recommendation-prompt-test`
  used `with-redefs` on `recommender/build-workflow-summaries` to stub
  the summary step while calling `recommender/build-recommendation-
  prompt`. Both functions moved to the `prompt` namespace, and
  `with-redefs` must target the exact var the code under test actually
  calls internally — updated to `prompt/build-workflow-summaries` and
  `prompt/build-recommendation-prompt`. `recommend-by-task-type-test`
  is unchanged; that function stays in the main namespace.

This is pure code motion aside from the one cosmetic rename above — no
detection, prompt, or recommendation logic changed.

## Fan-in Check

Grepped the fully-qualified namespace
(`ai\.miniforge\.cli\.workflow-recommender\b`) across `components/`,
`bases/`, and `projects/`. Two real callers found beyond the file
itself:

- `bases/cli/test/ai/miniforge/cli/workflow_recommender_test.clj` —
  updated (see above).
- `bases/cli/src/ai/miniforge/cli/workflow_runner/setup.clj` — calls
  only `recommender/recommend-workflow-with-fallback`, which did not
  move. No update needed; verified it still compiles and resolves
  against the split namespaces (see Testing Plan).

No project-level callers found under `projects/`.

## Testing Plan

- `stratum-lint` clean on all three resulting files (exit 0, was
  SL003 exit 1 on the original) and on both callers
  (`workflow_runner/setup.clj`, `workflow_recommender_test.clj`).
- `clj-kondo` clean on all five touched/new files.
- `clojure -M:poly check` — OK.
- `bb pre-commit` (commit-budget, lint, stratum-lint, 345-test smoke
  suite, GraalVM/Babashka compatibility suite) — green on both
  commits.
- Direct namespace verification (not just `bb test` change-scope,
  which can miss project-level callers and is unreliable under load
  per this program's established lessons):
  `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-
  recommender-test) (clojure.test/run-tests 'ai.miniforge.cli.
  workflow-recommender-test)"` — 2 tests, 11 assertions, 0 failures,
  0 errors.
  `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-
  runner.setup)"` — loads cleanly, confirming the one production
  caller still resolves against the split namespaces.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli` batch (task
  #31). Follows the sibling-namespace-under-a-subdirectory convention
  established by `loader.clj` (miniforge#1772) and `knowledge_safety.clj`
  (miniforge#1731).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb pre-commit` green (commit-budget, poly check, lint,
      stratum-lint, smoke tests, GraalVM compat)
- [x] Adversarial self-review: def set unchanged except one cosmetic
      local-var rename, documented above
- [x] Test call sites updated for the `with-redefs`-based prompt test
- [x] Zero unaccounted-for fan-in confirmed repo-wide before starting
