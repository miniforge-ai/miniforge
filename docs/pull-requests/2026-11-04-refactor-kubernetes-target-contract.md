<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Share the Kubernetes runtime target identity contract

## Overview

Extract the existing closed target identity schema into a pure foundation brick.
API surface class 1: internal EDN. Base: main after #2380.
The approved authority contract is tracked in #2381; this extraction is
independently compatible and grants no authority.

## Motivation

Native observations and exact OPSV grant scopes need the same target identity.
A lower shared contract avoids copying the schema into the generic grant component
or making grants depend upward on phase orchestration.

## Changes in Detail

Keep the existing cluster, namespace, namespace UID, and workload UID fields.
Preserve the closed-map and nonblank-string semantics and existing OPSV surface.
Move the original text predicate with the schema; preserve its whitespace
admission rather than substituting the newer generic Unicode text constraint.
Both affected product declarations explicitly include the shared component.
No new grant class, issuer, provider behavior, or backend activation.

## Testing Plan

Pin accepted target identities, every missing or invalid field, and closed shape.
Regress actual native preparation/discovery/measurement composition and packaging.
Run strict kondo, inferred strata, workspace/product checks, and normal hooks.
Serialize all JVM/build/hook runs. Full review and CI remain required.

Source checks pass: workspace check, strict kondo with zero errors/warnings,
seven namespaces' strata, four tests/87 assertions, and six detected faults
with zero test errors. Three unit cases/52 assertions each take less than 100 ms.
Native composition uses the existing inventory producer and public consumer.
Full product/package validation follows the implementation commit.

## Deployment Plan

Schema extraction only; no live cluster access or permission changes.
Native authority implementation follows separately.

## Related Issues/PRs

N7 completion; #2373, #2374, #2380, and approved authority amendment #2381.

## Checklist

- [x] Extract and adversarially review the shared contract.
- [ ] Validate source, project composition, and packaged behavior.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
