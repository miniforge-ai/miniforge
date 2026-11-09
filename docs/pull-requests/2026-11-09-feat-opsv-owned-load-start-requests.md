<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): prepare exact owned-load gate-release requests

## Overview

Read-only construction of gate release for a proven owned, unchanged,
never-started load Pod. Construction grants no permission and starts no traffic.

## Motivation

N7 §5.5 requires exact current authority for every forward effect. Successful
inert creation does not authorize releasing the worker scheduling gate.

## Changes in Detail

Reuse current retained recovery-plan admission,
durable creation ownership and live quiescence. Fence the patch with exact UID,
fresh resource version and the expected scheduling gates. Preserve original
forward load limits rather than substituting recovery duration.

The owner adversarial pass found that creation's requested-field subset matcher
could admit additional execution behavior before gate release. Eight real
producer regression cases failed with assertion failures and zero test errors
before correction. Start now compares the complete execution specification,
including unknown fields, while filling only named native defaults equally on
both sides. It refuses added environment, hooks, mounts, privileges, networking,
annotations, finalizers, controllers and unsupported default changes.
Creation acknowledgement semantics are unchanged.

The known values follow Kubernetes' native
[defaulting](https://github.com/kubernetes/kubernetes/blob/master/pkg/apis/core/v1/defaults.go),
[service-account admission](https://github.com/kubernetes/kubernetes/blob/master/plugin/pkg/admission/serviceaccount/admission.go),
[priority admission](https://github.com/kubernetes/kubernetes/blob/master/plugin/pkg/admission/priority/admission.go)
and [default toleration admission](https://github.com/kubernetes/kubernetes/blob/master/plugin/pkg/admission/defaulttolerationseconds/admission.go).
Custom admission extensions fail closed instead of becoming implicit permissions.

Share the conditional JSON Patch vocabulary across replica, stop and start
requests. Reuse one owned-load scope factory with explicit operation-specific
duration. Shared native creation and HTTP fixtures replace duplicated setup and
the previous multi-form callback; existing stop and cleanup cases remain covered.

## Testing Plan

Use real retained admission and durable native creation, with simulated HTTP
only. Validate canonical issuer compatibility and refusal of changed workers,
reused identity, revoked approval and unproven lifecycle state.

- Strict clj-kondo: zero warnings/errors; all 21 checked namespaces have at most
  three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package: 89 tests / 2,299 assertions each.
- Curated OPSV/workflow/artifact integration: 252 tests / 4,297 assertions.
- Native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Ten start, seven cleanup and five stop faults each cause assertion failures
  with zero test errors. They cover closed input, owned execution profile,
  lifecycle, distinct identity, gate/UID/revision preconditions, operation
  vocabulary, forward/recovery duration, native receipts and synchronous dispatch.

## Deployment Plan

No gate release, traffic or live experiments are authorized. A later native
provider still requires current N4/N8 admission, exact forward authority and a
pinned durable commit.

## Related Issues/PRs

Base branch: main. Builds on merged #2482 and the existing retained recovery APIs.

## Checklist

- [x] Prerequisite merged; implementation and real producer tests pass.
- [ ] Owner adversarial and full applicable pinned-standard review.
- [ ] Normal signed commits, budgets, full current-head review and CI.
- [ ] Fresh-main conflict check and normal owner squash merge.
