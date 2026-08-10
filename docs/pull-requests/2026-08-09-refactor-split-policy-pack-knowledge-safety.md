<!--
  Title: Split policy-pack/knowledge_safety.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split knowledge_safety.clj (rule 210)

## Overview

Splits the knowledge-safety pack's config and detector implementations
out of `ai.miniforge.policy-pack.knowledge-safety` into a new sibling
namespace, `ai.miniforge.policy-pack.knowledge-safety.detectors`,
resolving a stratum-lint SL003 finding (the combined namespace
measured 5 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2
continuation. Full-repo sweep found `components/policy-pack` still has
18 files over budget; `knowledge_safety.clj` (370 lines, zero fan-in
repo-wide) is one of this batch.

## Changes in Detail

- New file `knowledge_safety/detectors.clj`: `default-config`,
  `violation`, `all-injection-patterns`, `check-agent-source`,
  `first-violation-detector`, the accessor defs (`pack-id`,
  `pack-version`, `default-pack-roots`), `make-prompt-injection-rule`,
  and the actual `check-*`/`validate-*` detector functions
  (`check-trust-labels`, `check-instruction-authority`,
  `validate-pack-schema`, `validate-pack-dependencies-wrapper`,
  `check-pack-root`) — 3 layers.
- `knowledge_safety.clj`: rule definitions, the `custom-detectors`
  registration table, `create-knowledge-safety-pack`, and the
  registration side effect — now 2 layers (down from 5), since the
  rule defs and the registration map only reference `detectors/*`
  (qualified, cross-namespace) rather than same-file symbols.
- `first-violation-detector` changed from `defn-` to `defn` (now
  called from the main namespace's `custom-detectors` map, not just
  internally) — the only visibility change in this split.
- Registration keys (the quoted `:custom-fn` symbols rule defs carry)
  are unchanged — they still resolve under
  `ai.miniforge.policy-pack.knowledge-safety/...`, matching what
  `builders/create-rule` and any pack data expect; only the
  *implementations* moved.
- `knowledge_safety_test.clj`: three test groups
  (`check-trust-labels-test`, `check-instruction-authority-test`,
  `check-pack-root-test`) called the raw detector functions directly
  (white-box, bypassing the pack/registration path) — updated to
  `detectors/check-trust-labels` etc. Pack-assembly tests
  (`ks/create-knowledge-safety-pack`) are untouched.

This is pure code motion aside from the one required visibility change
and the test call-site updates above — no detection logic changed.

## Testing Plan

- `stratum-lint` clean on all three touched files (exit 0, was SL003
  exit 1 on the original).
- `bb test` (change-scope) green on the policy-pack component.
- Confirmed via repo-wide grep that none of the moved symbols
  (`violation`, `all-injection-patterns`, `first-violation-detector`,
  `pack-id`, `pack-version`, `default-pack-roots`, and all five
  detector fns) had any caller outside this component before moving
  them, aside from the three test groups now updated.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file. 16
more `policy-pack` files remain over budget, tracked separately.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation (see
  `workflow_runner.clj` splits miniforge#1662-#1667 and the
  `compliance-scanner` split #1580 for the established convention this
  follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb test` green (policy-pack change-scope)
- [x] Adversarial self-review: def set unchanged except one
      defn-→defn visibility flip, documented above
- [x] Test call sites updated for the three white-box detector tests
- [x] Zero fan-in confirmed repo-wide before starting
