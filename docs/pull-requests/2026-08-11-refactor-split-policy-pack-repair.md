<!--
  Title: Split policy-pack/repair.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split repair.clj (rule 210)

## Overview

Splits `ai.miniforge.policy-pack.repair` (4 real layers, over the rule
210 budget of 3) into three files: the original `repair.clj` keeps
only the orchestration (`succeeded?`, `attempt-repair`,
`attempt-repairs`), and two new sibling namespaces take the registry
storage/lookup and the built-in repair implementations,
`ai.miniforge.policy-pack.repair.registry` and
`ai.miniforge.policy-pack.repair.builtin`.

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2, policy
pack batch 2. `repair.clj` was a stratum-lint SL003 finding (4 real
strata vs the budget of 3, per the file's own docstring, which already
called out the split as owed). Zero fan-in repo-wide — nothing else
requires `ai.miniforge.policy-pack.repair` — so the split carries no
call-site risk, and no data (`.edn`) references any of its symbols.

## Changes in Detail

- New file `repair/registry.clj`
  (`ai.miniforge.policy-pack.repair.registry`): the `repair-registry`
  atom and its CRUD ops (`register-repair!`, `deregister-repair!`,
  `get-repair-fn`, `list-repairs`) — 2 layers, unchanged behavior.
- New file `repair/builtin.clj`
  (`ai.miniforge.policy-pack.repair.builtin`): the concrete repair
  implementations (`whitespace-repair`, `trailing-newline-repair`) —
  1 layer, unchanged behavior. Not registered at load time (unchanged
  from before the split) — a caller opts in via
  `repair.registry/register-repair!`.
- `repair.clj`: now only orchestration — `succeeded?` and
  `attempt-repair` (requiring `repair.registry` for
  `get-repair-fn`), and `attempt-repairs` — 2 layers.

This is pure code motion: no logic changed, only relocated and
re-namespaced. The def set is unchanged.

## Testing Plan

- `stratum-lint` clean on all three files (exit 0, was SL003 exit 1
  on the combined file).
- `bb test` (change-scope) green on the policy-pack component.
- No existing test file for this namespace (confirmed before
  starting); none added, since this is pure restructuring with no
  behavior change — see `workflows/tests-with-code`.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 policy-pack batch 2 (see
  `builtin_detectors.clj` split, and the original `workflow_runner.clj`
  splits miniforge#1662-#1667, for the established convention this
  follows).

## Checklist

- [x] stratum-lint clean on all three resulting files
- [x] `bb test` green (policy-pack change-scope)
- [x] Adversarial self-review: def set unchanged, only relocated
- [x] Zero fan-in confirmed repo-wide before starting (re-verified,
      not assumed from the task brief)
