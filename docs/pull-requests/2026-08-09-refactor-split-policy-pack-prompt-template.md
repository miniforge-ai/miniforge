<!--
  Title: Split policy-pack/prompt_template.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split prompt_template.clj (rule 210)

## Overview

Splits the default-template resource loading out of
`ai.miniforge.policy-pack.prompt-template` into its own sibling
namespace, `ai.miniforge.policy-pack.template-defaults`, resolving a
stratum-lint SL003 finding (the combined namespace measured 6 real
layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2
continuation. Full-repo sweep found `components/policy-pack` still has
18 files over budget; `prompt_template.clj` (229 lines, zero fan-in
repo-wide) is one of this batch.

## Changes in Detail

- New file `template_defaults.clj`: the EDN-resource-loading concern
  (`default-templates-resource`, `loaded-defaults`, `default-template`)
  — 3 layers, unchanged behavior.
- `prompt_template.clj`: template resolution and rendering
  (`interpolate`, `default-*-prompt/section`, `resolve-*-template`,
  `render-*`) — now 3 layers instead of 6, calling
  `template-defaults/default-template` instead of a same-file private
  helper.

This is pure code motion: no logic changed, only relocated and
re-namespaced. `default-template` was already private
(`defn-`) and had no callers outside this file, so no other call site
needed updating.

## Testing Plan

- `stratum-lint` clean on both files (exit 0, was SL003 exit 1).
- `bb test` (change-scope) green on the policy-pack component.
- Confirmed no other file in the repo referenced the moved private
  `default-template` symbol.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file. 17
more `policy-pack` files remain over budget, tracked separately.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation (see
  `workflow_runner.clj` splits miniforge#1662-#1667 and the
  `compliance-scanner` split #1580 for the established convention this
  follows).

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] `bb test` green (policy-pack change-scope)
- [x] Adversarial self-review: def set unchanged, only relocated
- [x] Zero fan-in confirmed repo-wide before starting
