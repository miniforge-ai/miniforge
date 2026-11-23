<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: retain chain input binding resolution records

## Overview

Retain the deployed binding vocabulary's resolution decisions in returned step outcomes.

## Motivation

The executor resolves bindings but discards which expressions supplied child inputs. N2 §14.2
requires resolved-binding evidence; recording these decisions is a prerequisite for binding edges.

## Changes in Detail

- Interpret each binding once through the existing resolver and describe the resulting input map.
- Distinguish omitted nil/missing values from resolved false values without changing legacy behavior.
- Preserve resolution records through child loading, execution failure, and identity-conflict outcomes.

## Testing Plan

Check pure binding records and public chain execution with controlled child ports, including failures.
Run consumers serially, then packaged checks and an adversarial Miniforge standards pass.

All three workflow consumers passed in 1m20s. The rebuilt CLI (39,135,369 bytes) passed isolated
binding and legacy-chain checks: 16 tests, 89 assertions, without production source overlays.
The explicit pinned standards pack reported zero violations across 4,360 files. Lint and strata checks passed.
The adversarial pass traced single resolution, child input delivery, loader/runner failures, conflicting
child identity, and projection. Records come from executor-owned resolution, not child-supplied metadata.

## Deployment Plan

These records are returned execution data, not durable edge acknowledgments or recovery authority.
Legacy bindings remain optional. Required-binding declarations, edge IDs/events, upstream evidence joins,
and durable recovery are subsequent slices; this change does not claim complete N2 conformance.

## Related Issues/PRs

Base: clean main at merged #2027. Layer: domain resolution reporting and its existing child boundary.
Independent of #2029 and #2030; later edge orchestration will consume both foundations.

## Checklist

- [x] Resolution records and failure regressions
- [x] Serial consumer and packaged verification
- [x] Adversarial standards review (normal signed hooks required at commit)
- [ ] Exact-head clean review and all CI before merge
