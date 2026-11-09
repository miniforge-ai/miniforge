<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): prepare native original-baseline restoration requests

## Overview

Prepare a fresh conditional replica-restoration request from an independently
approved retained recovery plan and the original baseline. This is read-only
construction, never execution authority or proof of recovered runtime state.

## Motivation

N7 §5.5 and §6.3 require original-baseline recovery with fresh preconditions.
The candidate's current replica count must never become the restoration target.

## Changes in Detail

Reuse current retained-plan admission, complete bounded native capture and the
existing original-baseline replica proposal constructor. Bind the distinct
effect, recovery duration and exact fresh payload. Caller counts, captures,
approval flags and foreign provider context/target settings are rejected.

The actual native capture requires the closed target declaration, without the
workflow correlation field; the original proposal still retains that field.
The first integration pass caught this shape mismatch before any HTTP calls.

A subsequent real-producer regression exposed a physical-cluster identity gap:
the same target alias and namespace/workload identities could conceal a changed
cluster root. That focused test failed with one assertion and zero errors before
correction. Native replica proposal construction now also compares the original
retained and current cluster-root UIDs. This applies to trial and restoration
requests; the alias is never substituted for physical identity.

## Testing Plan

Use actual retained approval and baseline artifacts with simulated HTTP only.
Prove original desired count, fresh UID/revision fencing, scope/issuer agreement,
changed-target refusal and no mutation. Preserve interruption and failure semantics.

- Initial combined source: 10 tests / 238 assertions, zero failures or errors.
- Nine faults each cause assertion failures with zero errors. They cover caller extensions,
  provider/effect identity, physical cluster identity in restoration and trial,
  original count, independent recovery duration, UID and revision fencing.
- Strict lint has zero warnings/errors; inferred namespace strata remain within
  the three-strata limit.
- Full JVM, curated integration and isolated fresh package validation remain
  pending before publication.

## Deployment Plan

No live experiments, mutations or restoration are authorized. Native execution
will still need exact current independent recovery authority and a pinned commit.

## Related Issues/PRs

Base branch: main. Builds on merged original-baseline proposal and recovery-plan
retention APIs. Full runner compensation and recovery verification remain separate.

## Checklist

- [ ] Implement and validate read-only native restoration construction.
- [ ] Owner adversarial and full applicable pinned-standard review.
- [ ] Normal signed commits, budgets, full current-head review and CI.
- [ ] Fresh-main conflict check and normal owner squash merge.
