<!--
  Title: Evidence optional-field projections
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: evidence optional-field projections

## Overview

Keep collected evidence compatible with the canonical contract by omitting
unavailable optional fields and normalizing the release producer's PR metadata.
Do not weaken validation of present values or alter opaque error details.

## Motivation

The canonical validator distinguishes omitted fields from invalid present values.
Local execution emits nil runtime metadata, release emits prefixed PR keys, and
phaseless anomalies emit a nil phase. Projection must normalize these live shapes.

## Changes in Detail

- Share a pure projection policy that omits nil without discarding false or zero.
- Normalize release PR keys while preserving legacy aliases when producer keys
  are absent. Explicit producer values take precedence, including nil.
- Project execution metadata and anomaly outcomes through that policy.
- Add producer-shape regressions and perform an adversarial standards review.

## Testing Plan

- Final focused JVM and packaged suites: 28 tests, 94 assertions, all pass.
- All three evidence consumers and CLI build pass.
- Added assembled-producer fixtures for downstream canonical validation.
- Kondo and inferred stratum lint pass. The root incremental standards scan
  indexes 4,107 files and reports zero changed-file findings. Normal signed hooks pass.
- Adversarial trace verifies live release keys through the response accessor,
  legacy aliases, absent runtime metadata, and direct/nested phaseless anomalies.
  False values survive projection; opaque error details retain nested nils.

## Deployment Plan

Normal CLI build; no stored evidence migration or validator relaxation.

## Related Issues/PRs

Based on main after outcome reliability validation (#1966). This is a prerequisite
for canonical evidence publication (#1959), not a claim of spec completion.

## Checklist

- [x] Shared projection policy and producer-shape regressions
- [x] Final JVM and packaged-runtime verification
- [x] Root incremental standards verification

Current-head Copilot review must have no new findings, and all CI checks,
including Build, must pass before merge.
