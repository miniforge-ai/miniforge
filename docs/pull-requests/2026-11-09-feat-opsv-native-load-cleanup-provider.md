<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): execute exact quiescent owned-load cleanup requests

## Overview

Connect the read-only cleanup constructor to the existing bounded native HTTP
and synchronous provider-dispatch boundary. This internal class-1 operation
requires separately admitted, exact recovery authority and a pinned commit.

## Motivation

N7 §5.5 requires separately authorized cleanup after proving worker quiescence.
An accepted deletion request is not proof of completed cleanup or recovered
baseline.

## Changes in Detail

Reconstruct current approved intent, ownership and
quiescence before one exact UID/revision-pinned, noncascading DELETE.
Handle documented native deletion acknowledgements without inferring absence.
Reuse owned-future transport and grant fixtures rather than duplicating lifecycles.
Stop and cleanup share ingress, Pod identity binding and outcome factories.
Shared stop behavior remains covered by the real existing integration cases.

Deletion alone accepts HTTP 200 or 202, with an exact owned Pod or a native
Success Status acknowledgement. Creation still requires 201; deadline patches
still require 200. No general 2xx policy is introduced. Status details follow
the native producer's plural `pods` resource kind and exact name/UID binding.
See the [Pod API](https://kubernetes.io/docs/reference/kubernetes-api/core/pod-v1/)
and [native deletion producer](https://github.com/kubernetes/apiserver/blob/master/pkg/registry/generic/registry/store.go).

## Testing Plan

Use actual retained admission, native creation, separate root recovery issuance,
independent grant pins and durable commits; simulate HTTP only. Verify current
authority, exact payload, quiescence, bounded callbacks and uncertain outcomes.
Validate both consuming products, a fresh CLI and an isolated package.

- Strict clj-kondo has zero warnings/errors; all changed namespaces have at most
  three inferred strata, including the shared fixture decomposition.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated own fresh package: 92 tests / 2,454 assertions each.
- Curated OPSV/workflow/artifact integration: 258 tests / 4,462 assertions.
- Existing native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Nine native-cleanup and seven constructor faults each produce assertion
  failures with zero test errors. They cover ingress, canonical reconstruction,
  identities, native receipt schemas, operation-specific status policies,
  synchronous dispatch, quiescence, cascading deletion and exact preconditions.

The final test-only split isolates the cross-thread fault from unrelated
late-exception callback cases. All ordinary callback cases remain covered.
The rerun first verifies production bytes against the freshly built own package;
unchanged JVM and native-compatibility proofs are retained without rebuilding.

## Deployment Plan

No live experiments, cluster mutations, deletions or credentials are authorized.
Current N4/N8 effect admission and separately scoped root grants remain mandatory.

## Related Issues/PRs

Base branch: main. Local development stacks signed PRs #2481 and #2482.
This slice will not be published before both merge.
N7 §5.5/§6.3; N10 §1.5. Baseline recovery and complete runner wiring remain separate.

## Checklist

- [ ] Both prerequisites merged on main before publication.
- [x] Implement and validate native cleanup dispatch.
- [ ] Owner adversarial and full applicable pinned-standard review.
- [ ] Normal signed commits, budgets, full current-head review and CI.
- [ ] Fresh-main conflict check and normal owner squash merge.
