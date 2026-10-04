<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Reconcile the N7 effective-actuation default

## Overview

Align N7.AC.1 with the recommendation-only effective default already required
by the minimal compliant implementation in §9. State that default in §5.3.

## Motivation

The table required PR-only by default while the MCI required recommendation-only.
Capability and execution permission are distinct: mandatory PR emission support
must not imply automatic external mutation.

## Changes

Keep the stable requirement ID and all §5.4 authority checks. Preserve mandatory
PR emission capability, disabled-by-default apply, and the optional apply contract.
Increment the document version and record this consistency correction.
Clarify the MCI capability bullet so it does not require PRs from recommendation-only runs.

## Verification and standards

Cross-checked N7 §§1.4, 5.3, 5.4, 7.2, 7.3 and 9 against N8's recommendation
fallback. No runtime behavior changes or implementation-completion claims are made.
Repository hooks, documentation lint and final-head CI remain required.

## Checklist

- [x] Adversarial consistency review
- [ ] Signed commit and repository hooks
- [ ] Final-head review settled and all CI green
