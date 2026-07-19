<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Separate Fatal-Only Exception Notes from Review Violations

## Overview

This PR makes the top-level compliance review honor the
exceptions-as-data carve-out for programmer-error guards.

## Motivation

The exceptions-as-data scanner already classifies programmer-error guards as
`:fatal-only`, and the embedded standard describes those rows as
informational. The top-level `bb review` path still flattened those rows into
the actionable `:violations` stream, so the review count overstated remaining
cleanup work.

This is scanner/reporting precision work, not a weakening of the policy:
runtime failures that should return anomalies remain reported as
`:cleanup-needed` violations, and the raw exceptions-as-data scan still keeps
classification counts available for audit.

## Changes in Detail

- Keep `:fatal-only` classification in the exceptions-as-data scanner.
- Filter `:fatal-only` rows out of the top-level `bb review` violation stream.
- Preserve `:cleanup-needed` rows as actionable standards violations.
- Add regression coverage for both the raw scanner behavior and the top-level
  review behavior.

## Testing Plan

- Focused compliance scanner tests.
- `bb review` to confirm the actionable count matches `:cleanup-needed`.
- `bb pre-commit` before merge.

## Deployment Plan

No runtime deployment impact. This changes compliance scanner reporting only.

## Related Issues/PRs

- Follows PR #1275.

## Checklist

- [x] Preserve raw scanner classification counts.
- [x] Exclude fatal-only rows from top-level actionable review output.
- [x] Confirm focused scanner tests pass.
- [x] Confirm `bb review` decreases to cleanup-needed count.
- [x] Run `bb pre-commit`.
