<!--
  Title: Split policy-pack/builtin_detectors.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split builtin_detectors.clj (rule 210)

## Overview

Splits the EC2 `instance_type` approval detector out of
`ai.miniforge.policy-pack.builtin-detectors` into its own sibling
namespace, `ai.miniforge.policy-pack.builtin-detectors.approved-instance-types`,
resolving a stratum-lint SL003 finding (the combined namespace
measured 4 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2
continuation (per-file namespace splits for components whose Wave 1
fix was a heading-only relabeling, not a real split). Full-repo sweep
found `components/policy-pack` still has 18 files over budget;
`builtin_detectors.clj` was picked first as the smallest (90 lines,
zero fan-in repo-wide — nothing else requires this namespace directly,
so the split carries no call-site risk outside its own registration
wiring).

## Changes in Detail

- New file `builtin_detectors/approved_instance_types.clj`: the
  detector's own logic (`default-approved-instance-families`,
  `instance-type-literals`, `approved?`, `approved-families`,
  `check-approved-instance-types`) — 3 layers, unchanged behavior.
- `builtin_detectors.clj`: now only the load-time registration wiring
  (1 layer). The registration KEY stays the original fully-qualified
  symbol (`ai.miniforge.policy-pack.builtin-detectors/check-approved-instance-types`)
  since `resources/policy_pack/packs/terraform-aws-1.0.0.pack.edn`
  references it as data (`:custom-fn`) — only the registered
  implementation moves, the data-facing name does not.

This is pure code motion: no logic changed, only relocated and
re-namespaced.

## Testing Plan

- `stratum-lint` clean on both files (exit 0, was SL003 exit 1).
- `bb test` (change-scope) green on the policy-pack component.
- Existing `builtin_detectors_test.clj` unchanged and passing — it
  exercises the detector through the real `detection/detect-custom`
  path (registry lookup), not by calling the moved function directly,
  so it is agnostic to which namespace implements it.

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
