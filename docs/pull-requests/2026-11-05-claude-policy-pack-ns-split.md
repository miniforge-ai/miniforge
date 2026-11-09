<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(gate): split policy_pack.clj into namespaces of at most three layers

## Overview

`components/gate/src/ai/miniforge/gate/policy_pack.clj` used nine layers
against the stratified-design budget of three (rule 210, stratum-lint SL003).
This PR moves its forms, unchanged, into seven namespaces under
`gate/policy_pack/`, each within three layers. Behaviour does not change.

## Motivation

The pre-commit hook fails SL003 on any staged copy of a file over the layer
budget. No change to the policy gate could be committed. The next PR needs
one. It scopes the gate's LLM-judge findings to the forms a change edits.
Dogfood runs stop failing on older findings elsewhere in the files they touch.

A split of an over-budget file cannot be sliced into smaller commits.
Every intermediate `policy_pack.clj` still has more than three layers and fails the
same hook. It lands as one commit, over the commit and PR line budgets.

MINIFORGE_PR_BUDGET_OVERRIDE: mechanical SL003 namespace split of one file; every moved form is byte-identical apart
from stratum metadata, visibility, and alias-qualified cross-namespace references, checked by script

## Changes in Detail

1. New namespaces under `ai.miniforge.gate.policy-pack.`, each holding the
   original forms:
   1. `worktree`: reading a changed file's entry from inside the worktree.
   2. `artifact`: resolving the artifact the gate evaluates and its per-file
      inputs.
   3. `judge`: the batched LLM-judge wiring.
   4. `rules`: selecting rules by phase, applicability and judge action.
   5. `checks`: running compiled checks and per-rule evidence.
   6. `packs`: the classpath standards pack.
   7. `evaluation`: compiled evaluation, the decision envelope, and gate
      errors.
2. `policy_pack.clj` keeps its namespace docstring, `repair-policy-pack`,
   `check-policy-pack-for-phase`, `check-policy-verify`,
   `check-policy-review`, the registry methods and the rich comment. Its
   public vars are unchanged, so `gate.interface` and the gate registry are
   untouched.
3. Forms used across the new namespaces change from `defn-` to `defn` (or
   lose `^:private`), and their stratum metadata follows each file's layers.
   References across namespaces are alias-qualified. Nothing else in any
   form changes.
4. `policy_pack_test.clj` calls `checks/classify-rules` instead of
   `#'sut/classify-rules`.

## Testing Plan

1. A script parsed every top-level form of main's `policy_pack.clj` and of
   the eight new files. After removing stratum metadata, `defn-`/`^:private`
   and the new alias prefixes, all 47 original forms appear exactly once, with
   no extra forms.
2. Every alias-qualified reference was read by hand; none rebinds a local.
3. All 22 gate test namespaces pass on the JVM: 216 tests, 841 assertions.
4. Under Babashka, on the `bb miniforge` task classpath, `ai.miniforge.cli.main`
   and `ai.miniforge.gate.interface` load and `check-policy-pack-for-phase`
   runs.
5. stratum-lint and clj-kondo report nothing on the changed files.

## Deployment Plan

No deployment step. The gate's behaviour and public vars are unchanged.

## Related Issues/PRs

Unblocks the change-scoped judge findings PR (`work/policy-judge-edited-forms.spec.edn`).

## Checklist

- [x] Every moved form verified unchanged by script
- [x] Gate tests pass on the JVM; the gate loads under Babashka
- [x] stratum-lint and clj-kondo clean
