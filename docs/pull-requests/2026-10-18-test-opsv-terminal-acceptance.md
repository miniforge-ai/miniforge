<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: Exercise terminal OPSV evidence recovery boundaries

## Overview and motivation

Complete the acceptance coverage split from terminal-evidence runtime PR #1948.
Tests distinguish irreversible provider outcomes from failed audit publication
and prove recovery cannot replay actuation.

## Layer and dependencies

Application acceptance tests, on merged #1948. No production behavior changes.

## Changes in detail

- Reopen a current journal after partial terminal publication. Retain its
  acknowledged prefix, publish the missing event once and never replay actuation.
- Exercise projection failure, untrusted event batches and correctable preflight
  rejection without misclassifying a rejected attempt as an irreversible effect.
- Recover through the shared workflow's persisted checkpoint after provider
  success and audit failure. Reject calls into the actual actuation entry point.
- Restore confirmed, reconciled, unknown and transient-audit outcome coverage,
  plus snapshot encoding failures. Preserve the legacy evidence-opt-in regression.
- Register all added suites in CI and the integration task.

## Testing plan

Run the four terminal suites serially from source and against the verified runtime
package, with only test paths overlaid. Check lint, inferred strata, composition
and the pinned standards scanner. Trace every failure assertion adversarially.

Source and isolated packaged runs both pass: 16 tests / 301 assertions. Scoped
kondo has zero warnings/errors, inferred strata stay within three layers, and the
pinned scanner reports zero violations across 4,451 scanned files. The adversarial
pass checked journal prefix retention, preserved failure outcomes and direct
actuation rejection across repeated recovery.

## Deployment plan

Tests only. The simulated provider does not perform external mutations. Production
host preparation remains separate; these fixtures do not claim host completeness.

## Checklist

- [x] Source and isolated packaged acceptance
- [x] Adversarial standards pass and static checks
- [ ] Signed commits, settled exact-head review and green CI
