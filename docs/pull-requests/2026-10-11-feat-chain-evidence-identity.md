<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: preserve admitted chain identity in evidence projections

## Overview

Retain admitted invocation, composition, and child-run identity in the existing chain evidence projection.

## Motivation

The executor now returns distinct run identity and its frozen definition, but the evidence summary drops them.
A summary must retain those references without confusing a definition keyword with an execution UUID.

## Changes in Detail

- Preserve canonical run identity and the admitted composition in evidence produced from current results.
- Carry actual child execution IDs in step summaries.
- Keep the legacy projection available for legacy results; do not infer historical run identity.
- Preserve compatible definition aliases for all results, deriving them from admitted identity when available.
- Return upstream anomalies unchanged instead of constructing synthetic execution evidence from an error.

## Testing Plan

Exercise admitted results, fresh-run separation, child identities, early failure, and legacy compatibility.
Run consumers serially, verify the packaged CLI, and audit the diff against Miniforge standards.

Validation: all three product consumers passed in 55 seconds. The rebuilt CLI (39,135,129 bytes)
passed isolated packaged regression checks: 10 tests, 45 assertions. The explicit pinned standards
pack reported zero violations across 4,360 files. Lint and namespace strata checks passed.
Adversarial review checked legacy aliases, admitted versus observed counts, upstream anomalies,
fresh run separation, and absent provenance; no new execution or recovery authority is implied.

## Deployment Plan

This is a reporting projection, not execution or recovery authority. Binding-edge evidence and full
reconstruction remain dependent work; no absent provenance is fabricated.

## Related Issues/PRs

Base: clean `main` at merged #2027. This projection does not require the open lifecycle PR #2029.
Layer: domain projection; small independent prerequisite for later chain evidence integration.

## Checklist

- [x] Projection and regression coverage
- [x] Serial consumer and packaged checks
- [x] Adversarial standards pass (normal signed hooks required at commit)
- [ ] Exact-head clean review and all CI before merge
