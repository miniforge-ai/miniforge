<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): observe owned-load quiescence without granting cleanup

## Overview

Expose `read-kubernetes-load-quiescence!` using the actual durable creation receipt,
canonical creation binding, and fresh provider UID observation. Return the raw
owned observation with one explicit `:load/quiescence` value.

## Motivation

N7 requires verified load termination before claiming successful recovery.
Ownership alone, terminal control-plane phase, and removal acknowledgements do
not establish that the worker stopped. Preserve unresolved outcomes explicitly.

## Changes in Detail

- `:not-started`: the original scheduling gate is intact, no node is assigned,
  and no regular, init, or ephemeral container activity is reported.
- `:terminated`: the sole non-restarting worker has actual identified termination
  state with ordered nonzero timestamps. Account for every possible worker;
  injected or unaccounted containers remain unresolved.
- `:unresolved`: owned state does not establish either of those facts.

Missing or foreign Pods remain ownership anomalies, not termination evidence.
Terminal Pod phase, elapsed deadline, deletion timestamp, and synthetic
`ContainerStatusUnknown` do not prove physical termination. Malformed provider
state remains unresolved. Every call rereads the durable receipt and live Pod.

These are read-only observations, not cleanup grants, recovery-plan admission,
successful restoration, or verified recovery. The current plan's separate
termination and cleanup requirements remain unchanged. No live experiment,
traffic, cluster mutation, image pull, or credential access is performed.

## Contract basis

Kubernetes scheduling gates cannot be added after creation; the gate must be the
one bound to the actual native creation request. See
[Pod scheduling readiness](https://kubernetes.io/docs/concepts/scheduling-eviction/pod-scheduling-readiness/).
The conservative terminal profile rejects control-plane-only synthetic unknown
states described in the
[node shutdown KEP](https://www.kubernetes.dev/resources/keps/3329/).

## Testing Plan

Validate the public boundary using actual native construction, independent root
issuance, pinned commit, and durable storage. Exercise fresh ownership, real
termination, synthetic and malformed state, other possible workers, and changes
after a previously successful observation. Simulate only HTTP transport.

Source and a freshly built isolated package pass 32 tests and 653 assertions.
Both consuming JVM products, Polylith structure and task-classpath checks pass.
Broader integration passes 211 tests and 2,875 assertions; native compatibility
passes 308 tests and 1,903 assertions. The isolated package uses only the current
JAR and test directories, without production-source overlays.

Strict lint has zero warnings or errors; all seven actual inferred call graphs
fit within three strata. All six injected faults cause assertion failures with
zero errors. Faults bypass scheduling, all-worker accounting, native termination,
identified timestamped state, fresh ownership, and required nonempty worker data.

Review caught that omitted or empty regular-container data could previously be
classified as not started. Require the nonempty collection before classifying
either outcome. The actual-producer regression failed before the fix with two
assertion failures and zero errors; both source and isolated package now pass it.

## Standards Gap Analysis

Pinned catalog: `72f451d3d7e0ad82079610bec61be0399a2b7117`.
Keep schema vocabulary, timestamp interpretation, pure observation classification
and bounded acquisition separate. Reuse the existing ownership boundary and
canonical scheduling-gate definition. The actual-call-graph check found a fourth
schema layer; extracting timestamp interpretation resolved it without a waiver.
The owner reviewed the final actual source, tests and lower-producer dependency
traces against all applicable pinned rules after formatting. No additional scoped
standards gap was found. No new effect authority or provider write path exists.

## Deployment Plan

No default activation, effects, live experiments, cluster mutations or credentials.
This API supplies observations only; callers still need separate current admission
and exact authority for any effect.

## Related Issues/PRs

N7 §5.5; N10 §1.5; #2450; #2461; #2467; #2475; #2478.

## Checklist

- [x] Owner whole-diff adversarial review against pinned applicable standards.
- [x] Separate timestamp interpretation, schemas, pure state classification, and
  bounded I/O composition; every actual call graph is at most three strata.
- [x] Reuse actual creation, durable transaction, and ownership producers in tests;
  simulate only HTTP responses, not authoritative records or approval results.
- [x] Strict lint, consuming JVM products, Polylith check, fresh CLI package.
- [x] Source and fresh isolated-package integration, compatibility, and targeted
  assertion-failing fault injections with zero harness errors.
- [ ] Normal signed commits below 200 reportable lines; PR below 600.
- [ ] Current-head full review, all comments settled, Test and Build, fresh-main
  conflict check, and normal guarded squash merge.

Native release/stop/cleanup, separate exact recovery grants, every-exit
compensation, baseline restoration and runner/CLI/Fleet integration remain N7
implementation obligations. This slice does not claim N7 completion.
