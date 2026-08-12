<!--
  Title: Split policy-pack/external.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split external.clj (rule 210)

## Overview

Splits `ai.miniforge.policy-pack.external` into three namespaces,
resolving a stratum-lint SL003 finding (the combined namespace measured
5 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, policy-pack Wave
2 batch 2. `external.clj` (189 lines) implements the external PR
read-only evaluation workflow and has real fan-in — `interface/builders.clj`
and `software_factory.clj` both re-export its `evaluate-external-pr` and
`parse-pr-diff`.

## Changes in Detail

- New file `external/diff.clj`
  (`ai.miniforge.policy-pack.external.diff`): `parse-diff-header` (Layer
  0), `parse-pr-diff` (Layer 1) — unified-diff-to-artifact parsing, 2
  layers.
- New file `external/matching.clj`
  (`ai.miniforge.policy-pack.external.matching`): `path-matches-glob?`
  (Layer 0), `files-match-globs?` (Layer 1), `pack-applies?` (Layer 2) —
  pack-applicability / glob matching, 3 layers.
- `external.clj`: keeps `select-applicable-packs` (Layer 0, now calls
  `matching/pack-applies?`) and `evaluate-external-pr` (Layer 1, now
  calls `diff/parse-pr-diff`) — 2 layers (down from 5).
- `interface/builders.clj` and `software_factory.clj`: both re-exported
  `parse-pr-diff` from `external`; that def moved to `external.diff`, so
  both now additionally require
  `[ai.miniforge.policy-pack.external.diff :as external-diff]` and
  point their `parse-pr-diff` re-export there. `evaluate-external-pr`
  stayed in `external.clj`, so those re-exports are unchanged.
- `external_test.clj`: the diff-parsing tests
  (`parse-pr-diff-test`) called `external/parse-pr-diff` directly
  (white-box); updated to require `external.diff` and call
  `diff/parse-pr-diff`. The `evaluate-external-pr` tests are untouched.

This is pure code motion — no detection/evaluation logic changed, no
`def` added, removed, or renamed (only relocated).

## Testing Plan

- `stratum-lint` clean on all five touched/added source files (exit 0;
  `external.clj` alone was SL003 exit 1 before this change).
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.policy-pack\.external\b`) across `components`,
  `bases`, and `projects` found exactly the three call sites listed
  above (plus the file itself); no `projects/` integration-test caller
  exists for this namespace.
- `bb test` (change-scope) green on the policy-pack component.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation (see
  `workflow_runner.clj` splits miniforge#1662-#1667 and
  `knowledge_safety.clj` #1731 for the established convention this
  follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb test` green (policy-pack change-scope)
- [x] Adversarial self-review: def set unchanged, relocated only
- [x] All three external call sites updated (2 src re-exports + 1 test)
- [x] Fan-in confirmed repo-wide (components/bases/projects) before
      starting; zero `projects/` callers found
