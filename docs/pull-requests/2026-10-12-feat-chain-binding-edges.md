<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: publish current chain binding edge lifecycle

## Overview

Represent predecessor input binding as a distinct operation between actual child executions.

## Motivation

N1 §2.32 and N3 §3.12.1 distinguish binding edges from workflow steps. Step events alone cannot
describe whether input transfer succeeded before the downstream workflow was loaded or executed.

## Changes in Detail

Adds current-mode edge identity, publication around binding resolution, fail-closed child admission,
and retained edge results. Literal and chain-input expressions are not predecessor workflow edges.
Never synthesize an upstream UUID when no predecessor execution exists.
Resolved edge records preserve the source workflow UUID and encode the deployed vector expression as EDN text
in `:binding/source-output-path`. This describes the existing expression, not a new definition selector syntax.
Chain evidence retains edge records and step binding records without inventing provenance for legacy results.
Binding reports reuse the shared redactor with target and source naming context. Execution inputs remain unchanged.
Evidence reapplies that policy to imported binding records and redacts its projected snapshot.
The projected snapshot is reporting data, not recovery authority.

## Testing Plan

Verify real child identities, ordering, binding-only completion, failure propagation, and durable event reopening.
Use serial consumers, isolated packaged checks, and an adversarial standards pass before pushing.

All three workflow/evidence consumers passed in 1m30s. The JVM real-storage suites passed 14 tests,
112 assertions. The rebuilt CLI (39,147,268 bytes) passed 39 tests, 276 assertions from an isolated directory
without production source overlays. The explicit standards pack reported zero violations across 4,384 files.
Lint and namespace strata checks passed. A prior evidence PR's consumer count is corrected from four to three.

Adversarial review caught early dependency inspection bypassing legacy binding-error handling and child-supplied
edge metadata surviving no-edge paths. Both are corrected and covered. Additional regressions verify absent
predecessors, optional missing values, fresh identities, receipt mismatch, and original failure precedence when
edge and step publication both fail. No test performs live provider effects.
Review finding 4245900474 exposed missing naming context around retained binding values. Regressions now cover
target names, source paths, literal expressions, nested secrets, opaque values, and imported evidence.
The end-to-end test verifies that an upstream credential reaches the child unchanged but is absent from evidence.

## Deployment Plan

Existing deployed bindings remain optional. This slice does not invent required-binding declaration syntax,
authorize execution recovery, or claim that returned records alone satisfy durable evidence reconstruction.

## Related Issues/PRs

Base: clean main at merged #2031 (`06cfa2538`). Layer: binding-edge application orchestration.
Dependencies: #2029 (durable lifecycle) and #2031 (resolution records), both merged.

## Checklist

- [x] Edge lifecycle and records
- [x] Failure and real-storage regressions
- [x] Serial consumers, packaged verification, and adversarial standards pass
- [ ] Normal signed hooks, exact-head clean review, and all CI before merge
