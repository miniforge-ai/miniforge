<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): prepare exact quiescent owned-load cleanup requests

## Overview

Prepare a conditional cleanup request only from current retained recovery intent,
durable creation ownership and a fresh quiescent Pod observation. This is a
read-only internal class-1 API, not deletion authority or a cleanup receipt.

## Motivation

N7 §5.5 requires separately authorized cleanup without deleting unproven or still
active resources. A stop acknowledgement is not proof of worker termination.

## Changes in Detail

Reuse current retained-plan admission, ownership and quiescence boundaries.
Construct an exact UID/resource-version-pinned request with a distinct effect
identity. Caller-supplied ownership or termination flags are not accepted.
The closed input contract is shared with stop-request preparation, without a
second copy of that schema.

Stop and cleanup share one pure recovery-scope factory. It binds the current
owned UID, retained target, comparison, effect identity, limits and payload hash.
Stop-request behavior is unchanged and remains covered by the existing tests.

Deletion uses `Orphan`, not cascading garbage collection: only the Pod UID is
proven owned. Kubernetes documents these choices in its
[DeleteOptions contract](https://kubernetes.io/docs/reference/kubernetes-api/definitions/delete-options-v1-meta/).
A matching-UID deletion must not delete arbitrary dependent resources outside the
retained plan. The regression failed before this correction: two assertions,
zero test errors, using the real retained-plan and native-creation producers.

## Testing Plan

Use actual retained admission, native creation and durable records with simulated
HTTP only. Test never-started and physically terminated workers, unresolved or
foreign observations, changed intent, exact payload binding and no mutation.
Validate affected products, a fresh CLI and an isolated package.

- Strict clj-kondo: zero warnings/errors; seven changed Clojure namespaces have
  at most three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package: 62 tests / 1,262 assertions.
- Curated OPSV/workflow/artifact integration: 228 tests / 3,270 assertions.
- Native compatibility: 308 tests / 1,903 assertions across the existing 42 suites.
- Six injected faults cause assertion failures with zero test errors.
  They cover closed input, quiescence, distinct identity, cascading deletion,
  UID preconditions and resource-version preconditions.

The integrated fixtures use real retained approval, native creation and durable
records. Only HTTP is simulated. No cleanup request grants permission or proves
that deletion completed.

## Deployment Plan

No live experiments or deletions are authorized. Execution will require separate
current effect admission, an exact recovery grant and a pinned durable commit.

## Related Issues/PRs

Base branch: main. Depends on merged #2475, #2478, #2479 and #2480.
N7 §5.5/§6.3; N10 §1.5. Native cleanup execution is a later dependent slice.

## Checklist

- [x] Implement and validate read-only construction.
- [x] Owner adversarial and full applicable pinned-standard review.
- [ ] Normal signed commits, budgets, full current-head review and CI.
- [ ] Fresh-main conflict check and normal owner squash merge.
