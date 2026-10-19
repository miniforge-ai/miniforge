<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish native phase completion facts

## Purpose and layer

Application integration for N3 phase completion: carry authoritative artifact
receipts through native OPSV results and project those facts with the measured
duration into workflow events. Missing facts remain missing, not fabricated empty
vectors. Cumulative references are not evidence of what this phase produced.

## Dependencies and scope

Branched from main. Depends on #2055 (per-attempt acknowledgment
facts) and #2054 (publication refusal propagation), both required before merge.
Keep the default-host migration, workflow intent, and terminal workflow events in
their own later slices. Preserve error output and explicit empty receipts.

## Verification plan

Trace actual phase lifecycle results through runner projection into current
journals; verify durable reopen, partial failure, absent facts, and invalid facts.
Check all affected product consumers and an isolated rebuilt CLI. Apply the
adversarial standards pass, normal signed hooks and budgets, exact-head review,
and all CI gates. No storage migration; normal CLI deployment.

## Recovery contract

A bundle-only republication acknowledges only the bundle in that new attempt.
It does not claim to republish the earlier phase material. Recovery assertions
check that exact receipt, while requiring all other retained output (including
the sealed bundle, cumulative references, and provider outcome) to remain equal.
Provider calls must not repeat.

## Local verification and adversarial review

- Focused source tests: 16 tests, 213 assertions, passing.
- All three product consumers of workflow and phase-opsv: passing (3m06s), serially.
- Rebuilt CLI: 39,185,155 bytes. Isolated package, no production source overlays:
  38 tests, 671 assertions, passing, including current-stream and terminal recovery.
- Kondo: no warnings or errors. Inferred strata: no changes required.
- Reviewed receipt provenance, partial publication, missing versus empty facts,
  checkpoint refusal before publication, sealed-bundle invariance and recovery
  without provider replay. Shared test factories avoid duplicate fixture maps.

The native lifecycle/publisher/journal test is not a full-runner or default-host
acceptance claim. Those remain separate work.
