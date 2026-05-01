<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# docs: pack interchange, control surface, and per-workflow streaming amendments

## Overview

Six related amendments that close real OSS contract gaps surfaced by
implementation pressure: pack signature and bundle formats so signed packs are
portable between miniforge installs; the tool registry hoisted from informative
to normative so the capability-grant gate has something canonical to enforce
against; checkpoint and model-override control actions in the OCI surface;
the per-workflow event-stream wire contract; and the TaskExecutor protocol
hoisted to normative so substrate plug-ins (Docker, Kubernetes, worktree)
have a stable contract.

Documentation-only. No code or test changes.

## Motivation

Each amendment fixes a place where the OSS contract was either informative-only
(so downstream callers couldn't depend on it without breakage), sketched
(so two correct implementations would not interoperate), or absent
(so downstream concerns had to invent contracts that should be shared).

The deltas were identified by trying to write a second consumer against the
existing OSS contracts — once a second consumer exists, every gap that the
first consumer paved over becomes load-bearing.

Areas that are clearly outside the OSS scope as currently framed (multi-tenant
control planes, cross-instance fleet aggregation transport, mandatory remote
persistence backends) are deliberately not part of this PR — those belong
downstream of OSS in the products that need them.

## Changes In Detail

### N1 — Architecture (0.5.0 → 0.6.0)

- **§2.10.4.1 Pack Signature Format.** §2.10.4 already specified key
  management (ed25519, KRL, rotation) but not the signature artifact itself.
  This section defines a detached `pack.sig` (single-line EDN) that signs the
  canonical `:pack/content-hash`, an optional `pack.sig.pub` for embedded
  public-key bundles, optional counter-signature chains, and a pure
  `verify-pack-signature` API contract whose result enumerates the failure
  reason. Without this, two OSS implementations could both "support pack
  signing" and still not accept each other's signed packs.

- **§2.10.6 Pack Bundle Format.** §2.10.3 specified the manifest data shape
  but not the on-disk artifact a runtime loads or a registry stores. This
  section defines a gzipped tar archive (`.pack.tar.gz`), the directory
  layout (`pack.edn`, `entrypoints/`, `templates/`, `policy-fragments/`,
  `schemas/`, `resources/`), the canonical-EDN serialization used to compute
  `:pack/content-hash` over the bundle, and bundle-integrity validation
  obligations at install and run time.

- **§2.31 Tool Registry.** Capabilities use the pattern
  `<connector>.<resource>.<action>` (e.g., `github.pr.read`). N10 §11
  referenced "the tool registry (N1 §2.3)" but N1 §2.3 is the Agent
  definition; the actual registry was documented only in
  `informative/tool-registry.md`. Without a normative tool-registry contract,
  the capability-grant gate (N4 §5.1.9) had no canonical surface to enforce
  against. This section defines the tool descriptor schema; four tool types
  (`:function | :lsp | :mcp | :external`); discovery order
  (built-in → user → project, with downstream products free to slot
  additional roots below project); capability resolution and binding; and
  capability enforcement at every connector invocation.

### N3 — Event Stream (0.7.0 → 0.8.0)

- **§5.3 Streaming Endpoints (HTTP).** Previously a one-line SSE sketch with
  one example response line. Insufficient for any consumer outside the
  process emitting the events. This rewrite specifies the per-workflow wire
  contract:
  - §5.3.1 Endpoint (per-workflow SSE; optional WebSocket)
  - §5.3.2 Authentication (Bearer header; query-param fallback for browsers
    that can't set headers on `EventSource`)
  - §5.3.3 Listener Attach Handshake (the connection IS the N8 §2.1
    listener attach; emits `listener/attached` first, `listener/detached`
    last)
  - §5.3.4 Subscription Filters (server-side; un-filtered events MUST NOT
    cross the wire)
  - §5.3.5 Resume-from-Sequence (`?from-sequence=` and standard
    `Last-Event-ID`; HTTP 410 if past retention)
  - §5.3.6 Backpressure and Buffer Overflow (drop-oldest vs disconnect;
    consistent within a deployment)
  - §5.3.7 SSE Wire Format (event/id/data/retry; heartbeats every ≤30s)
  - §5.3.8 WebSocket Wire Format (optional)
  - §5.3.9 Rate Limiting and Quotas
  Cross-workflow aggregation endpoints are explicitly out of OSS scope.

### N6 — Evidence & Provenance (0.5.0 → 0.6.0)

- **§3.1.1 Artifact Types.** Adds `:pr-context-pack` to the External PR
  artifact types with the full content schema (PR id, provider, repo, diff
  summary, metadata, CI status, review status, readiness, risk, capture
  timestamp). PR Context Packs are the normalized PR snapshot that reviewer,
  meta, and governance workflow packs consume; registering the artifact type
  makes the contract portable across packs and across N9 ingestion
  implementations.

### N8 — Observability Control Interface (0.2.0 → 0.3.0)

- **§3.1.5 Checkpoint Control.** `checkpoint.request`, `checkpoint.approve`,
  `checkpoint.reject` for governed pause-points in Workflow Packs (N1 §2.24)
  and chained workflows (N2 §14). When reached, the workflow transitions to
  `:paused-awaiting-checkpoint`, emits `checkpoint/reached`, and waits for an
  approve/reject. Expiration handling is normative.

- **§3.1.6 Model Control.** `model.override`, `model.set`, `model.clear` for
  policy-bounded LLM model substitution. Justification is required (empty
  reason MUST fail). Allowed-models policy check is normative. Expiration is
  enforced. Silent fallback on unavailability is forbidden when the operator
  declares `:model/fallback-allowed? false`. Overrides MUST NOT persist
  across workflow boundaries.

- **§10 Event Stream Extensions.** New events `checkpoint/reached`,
  `checkpoint/expired`, `model/overridden`, `model/override-expired`.

### N9 — External PR Integration (0.1.0 → 0.2.0)

- **§9.1 New Artifact Types.** Adds `:pr-context-pack` to the list of N9
  artifacts with the obligation that ingestion emits the artifact on PR
  creation and on significant updates (diff change, CI state change, review
  state change, base-branch change). Schema lives in N6 §3.1.1; this section
  defines the emission contract.

### N11 — Task Capsule Isolation (0.1.0 → 0.2.0)

- **§10 TaskExecutor Protocol.** Hoisted from `informative/I-DAG-ORCHESTRATION.md`
  and `informative/I-TASK-EXECUTOR.md` to normative. Defines:
  - §10.1 Protocol methods (`executor-type`, `available?`,
    `acquire-environment!`, `executor-execute!`, `copy-to!`, `copy-from!`,
    `persist-workspace!`, `restore-workspace!`, `release-environment!`)
  - §10.2 Method requirements (idempotency, ordering, persistence kinds,
    workspace digest)
  - §10.3 Executor registry and selection (no silent downgrade per N11.MD.1)
  - §10.4 Evidence emissions per lifecycle method
  - §10.5 Substrate extensibility — the protocol intentionally does not
    require any specific substrate or persistence kind beyond the `:git`
    baseline. Cross-node capsule binding and additional persistence backends
    are deployment decisions made by downstream products.
- §11–§14 renumbered (was §10 Implementation Mapping → §11; §11 Conformance
  → §12; §12 New Definitions → §13; §13 References → §14).
- §1 scope listing updated.

### SPEC_INDEX (0.6.0 → 0.7.0)

- Top-level version bumped; new history entry summarizes the per-spec bumps.

## What is NOT in this PR (intentionally out of scope)

The audit that surfaced these gaps also surfaced concerns that belong
downstream of OSS, not in OSS:

- **Multi-tenant runtime data and enforcement.** OSS does not currently model
  organizations or tenants in the runtime data; pulling that in would change
  the OSS product framing and conflict with the supervisory-control-plane
  deferral in N5-delta. Stays downstream.
- **Cross-workflow / cross-instance event-stream aggregation.** The
  `/api/fleet/stream` endpoint, multi-tenant filtering, per-tenant sequence
  numbering, and remote listener semantics are control-plane transport, not
  per-workflow event semantics. Stays downstream.
- **Object-store / cross-node workspace persistence as a required behavior
  for any substrate.** OSS defines `persist-workspace!` / `restore-workspace!`
  with `:git` as the required baseline. Operational requirements like
  "Kubernetes pods reschedule across nodes therefore object-store is
  required" are deployment decisions, not contract requirements. Stays
  downstream.

These are real gaps for products that do multi-tenant fleet operations, but
they should land in those products' specs — not here.

## Testing

Documentation-only. No code touched. The pre-commit hook on this branch ran
806 tests on bricks modified by unrelated WIP on main (event-stream, operator,
pr-lifecycle, supervisory-state, workflow); 4,093 assertions, 0 failures.

Reviewers should verify:

- [ ] Section numbering consistent (especially N11 renumber §10–§13 → §11–§14)
- [ ] Cross-references resolve (e.g., N6 §3.1.1 `:pr-context-pack` schema
      matches N9 §9.1 emission description)
- [ ] TaskExecutor protocol method signatures realizable against existing
      Docker/K8s/Worktree implementations without requiring substrate-specific
      changes
- [ ] No remaining language that pulls product-boundary concerns into OSS
      (the previous revision of this PR did and was rejected by review)

## Checklist

### Required

- [x] Documentation-only change; no test impact
- [x] Pre-commit validation passes (no `--no-verify`)
- [x] No commented-out tests
- [x] No giant functions (n/a — docs)
- [x] Functions are small and composable (n/a — docs)
- [x] New behavior has tests (n/a — docs)

### Best Practices

- [x] Code follows development guidelines (n/a — docs)
- [x] Linting passes (markdown only)
- [x] README/docs updated if needed (SPEC_INDEX bumped)
- [x] PR doc created (this file)
- [x] Focused PR (single concern: pack interchange, control surface, and
      per-workflow streaming amendments)

## Related

- Originally bundled tenant-scoping and cross-fleet streaming amendments;
  those were removed after review identified them as product-boundary
  violations and will land in their respective downstream products.
