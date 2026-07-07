<!--
  Title: content-scan multiline match mode
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: content-scan :multiline? match mode (Finding 7)

Branch: `fix/content-scan-multiline`

## Summary

Finding-7 engine fix. `detect-content-scan` matches each line independently, so a
pattern that spans lines can never match. `k8s/require-resource-limits`'s pattern
(`resources:\n  limits:`, negative mode) therefore never matched — and after the
mode-negative fix (#1388), it fired on **every** manifest, including compliant
ones with limits.

Add a `:multiline?` detection flag: when set, patterns match against the whole
content instead of per-line. `require-resource-limits` now declares it, so a
manifest with a resource-limits block passes and one without fires.

## Changes

- `detection.clj`: `any-pattern-matches-multiline?` + a `:multiline?` branch in
  `detect-content-scan`.
- `schema.clj`: `:multiline?` on `RuleDetection`.
- `kubernetes` pack: `require-resource-limits` sets `:multiline? true`.

## Test plan

- New `require-resource-limits-multiline-test`: a manifest WITH limits passes
  (was firing pre-fix); WITHOUT fires.
- policy-pack detection + schema + standard-packs: 72 tests, 643 assertions, 0
  failures.
- `bb poly:check` clean.

## Related

- Governance audit Finding 7 (the per-line engine limitation noted in
  `docs/detection-quality-audit-2026-07-06.md`).
