<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: issue runtime-owned effect grants

## Overview

Adds the production execution-grant issuer for exact merge and deployment
transactions. Runtime policy—not caller input—now determines principal, scope,
expiry, ceilings, and delegation state.

## Layer

Execution-grant domain policy and public application boundary.

## Depends on

- #1700 — durable authorization and commit claim
- #1701 — durable reconciliation fencing
- #1702 — completed effect-transaction specification metadata

## Changes

- Validate closed, effect-specific merge and deployment issuance requests.
- Derive exact transaction scope from one explicit effect-policy catalog.
- Require an active workflow identity and allow-class runtime preflight.
- Refuse issuance when revocation-for-cause history disqualifies the principal.
- Normalize a nil Kubernetes context to the configured default binding.
- Validate the grant returned by the existing root issuance factory.

## Validation

- `bb lint:clj` and `bb lint:stratum` pass with no findings.
- `bb poly:check` reports 0 errors and 0 warnings.
- Execution-grant tests pass in Miniforge, Core, and TUI.
- Full `bb test` and the compliance review pass.

## Deployment Plan

Merge to `main`. The follow-up merge and deployment enforcement specs add the
runtime call sites; this PR introduces no irreversible-effect invocation.
