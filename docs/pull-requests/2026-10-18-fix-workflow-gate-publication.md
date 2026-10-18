<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Require acknowledged workflow gate decisions

## Overview and motivation

N3 gate decisions must be durably acknowledged before a gated phase advances.
A missing stream, rejected receipt or publication exception must block dependent
leave processing while preserving the phase output and decision envelope.

## Layer and dependencies

Workflow application orchestration, based on main. Builds on the merged N3 gate
event contracts and producers. Separate publication acknowledgment from gate-result
projection. Gate denial remains denial, but now reaches the actual phase status
and inner error result that the execution state machine reads.

## Changes in detail

- Require matching decision identity, payload and an assigned journal position.
- Accept all supported host stream locations and actual phase-result names.
- Preserve anomaly values and interruption; do not swallow fatal JVM errors.
- Mark the actual phase lifecycle failed when publication is unconfirmed.
- Fail closed when envelope minting fails, and ensure acknowledged denial cannot
  advance through a stale completed status. Preserve structured gate failures.
- Share test constructors instead of repeating context and phase-result maps.

## Testing plan

Exercise acknowledged legacy and current journals, missing streams, malformed
receipts, publication failures and dependent-leave suppression. Run scoped lint,
inferred-stratum checks, workflow consumers and packaged integration coverage.

All three workflow consumer suites pass serially. The final isolated CLI package
passes 26 tests / 168 assertions, including real current-journal close/reopen.
Scoped kondo reports zero warnings/errors; inferred strata and pinned standards
scan are clean. Existing DAG hint publishers retain their previous behavior.

## Deployment plan

Hosts running gated workflows must supply a functioning event stream. Failure
leaves the phase blocked; it cannot turn an unaudited decision into permission.
Gate rule publication and policy-pack decomposition remain a separate slice.

## Checklist

- [x] Legacy and current-journal regressions
- [x] Standards adversarial review and composition checks
- [x] Consumer and packaged verification
- [ ] Signed commits, settled review and passing CI
