<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix: Policy Gate Phase Wiring

## Overview

Wire phase policy gates to evaluate the implemented code artifact through compiled policy checks when verify or review
phase outputs are not code artifacts.

## Motivation

`policy-verify` and `policy-review` are present in phase defaults, but the generic gate runner passes the phase output
to every gate. Verify outputs test metadata and review outputs a review verdict, so pack-derived content rules can
silently miss the code under review.

## Changes in Detail

- Add policy-gate artifact resolution from `:execution/phase-results :implement :artifact`.
- Preserve current behavior when the phase artifact already contains code.
- Run policy packs through `compile-pack-checks`, including fail-loud semantic and compile-error handling.
- Require built-in gate capabilities before compiled pack evaluation so mechanical checks are registered without relying
  on caller load order.
- Add regression coverage for direct policy-gate checks and the generic workflow gate path.

## Testing Plan

- `clj-kondo --lint components/gate/src/ai/miniforge/gate/policy_pack.clj
  components/gate/test/ai/miniforge/gate/policy_pack_test.clj
  components/workflow/test/ai/miniforge/workflow/phase_transitions_test.clj`
- `clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}' -M:dev:test -m
  cognitect.test-runner -d components/gate/test -d components/workflow/test -n ai.miniforge.gate.policy-pack-test -n
  ai.miniforge.workflow.phase-transitions-test`
- `clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}' -M:dev:test -m
  cognitect.test-runner -d components/gate/test -d components/workflow/test`
- `bb pre-commit`

## Deployment Plan

Merge after CI and review comments are resolved. No migration required.

## Related Issues/PRs

- Depends on #1237.
- Covers `work/policy-gate-phase-wiring-and-evidence.spec.edn`.

## Checklist

- [x] Code artifact is used for verify/review policy gates.
- [x] Existing review verdict gates still validate review output.
- [x] Policy gate failures remain on the existing gate execution path.
- [ ] CI and review comments are resolved.
