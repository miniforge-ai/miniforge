# docs: Fleet enablement amendments to N-spec contracts (G1–G8)

## Overview

Close eight OSS specification gaps that block Fleet (the commercial extension)
from reaching its N10/N11/N12 Minimal Compliant Implementation. The miniforge
core specs already covered the data shapes for Workflow Packs, Pack Runs, OCI,
and PR Work Items, but Fleet implementation surfaced several missing wire
contracts, on-disk formats, and runtime protocols that Fleet cannot legitimately
work around without inventing parallel concepts.

This PR is documentation-only. No code changes, no test changes. It bumps
seven normative specs and the SPEC_INDEX.

## Motivation

Fleet's `miniforge-fleet-specs` repo defines normative N10 (Workflow Pack
Marketplace), N11 (Native Control Console), and N12 (Enterprise Add-ons) that
extend the OSS core. An audit against the OSS specs identified eight gaps where
the OSS contract was either informative-only, sketched, or absent — leaving
Fleet's implementation work without a stable foundation:

- The K8s object-store workspace persistence work (Fleet `work/11`) referenced
  a `TaskExecutor` protocol that lived only in informative docs.
- The capability-grant gate (N4 §5.1.9) referenced a "tool registry (N1 §2.3)"
  that didn't exist normatively — N1 §2.3 is the Agent definition.
- N1 §2.10.4 specified pack-signing key management but not the signature wire
  format or verification API; registries had no interoperable contract.
- N1 spec'd the pack manifest data shape but not the on-disk archive layout
  that registries store and runtimes load.
- N3 §5.3 sketched an SSE endpoint with one example line but no auth, no
  resume-from-sequence, no listener attach handshake — none of which a Fleet
  listener attaching to remote miniforge instances can do without.
- Fleet N10 §6 defined a `:pr-context-pack` artifact type Fleet-side, but it
  was not registered in OSS N6/N9.
- Fleet N11 §1.1 referenced `checkpoint.request` / `model.override` /
  `model.set` OCI actions that did not exist in N8.
- Workflow / Pack Run / Evidence / Event schemas had no `:tenant/id` field;
  Fleet had to bolt tenancy on as an external mapping.

Each gap closure is the smallest contract that lets Fleet build cleanly without
re-litigating the OSS trust or runtime model.

## Changes In Detail

### N1 — Architecture (0.5.0 → 0.6.0)

- **§2.10.4.1 Pack Signature Format** (G4): detached `pack.sig` and
  `pack.sig.pub` files; signs the canonical content-hash; chain signatures for
  multi-signer; pure `verify-pack-signature` API contract.
- **§2.10.6 Pack Bundle Format** (G3): `.pack.tar.gz` archive; directory layout
  (`pack.edn`, `entrypoints/`, `templates/`, `policy-fragments/`, `schemas/`,
  `resources/`); canonical EDN serialization rules for content-hash;
  bundle-integrity validation requirements.
- **§2.31 Tool Registry** (G2): tool descriptor schema; four tool types
  (`:function | :lsp | :mcp | :external`); discovery order; capability
  resolution and binding; capability enforcement at invocation; tool health
  state.
- **§2.32 Tenant** (G6): tenant identity schema; the canonical list of core
  schemas extended with `:tenant/id`; propagation rules (origin from
  authenticated principal, inheritance, chaining, pack runs, external PRs);
  enforcement; informative tenant hierarchy.
- **§2.1 Workflow** and **§2.26 Pack Run** schemas: `:tenant/id` field added
  (OPTIONAL in OSS local mode — implicit `"local"` — REQUIRED in Fleet).

### N3 — Event Stream (0.7.0 → 0.8.0)

- **§5.3 Streaming Endpoints (HTTP)** (G5): rewritten from a one-line sketch.
  Now defines:
  - §5.3.1 Endpoints (per-workflow + cross-fleet SSE; optional WebSocket)
  - §5.3.2 Authentication (Bearer + access-token query param fallback for SSE)
  - §5.3.3 Listener Attach Handshake (maps to N8 §2.1; emits
    `listener/attached` and `listener/detached`)
  - §5.3.4 Subscription Filters (server-side filtering; multi-tenant
    enforcement)
  - §5.3.5 Resume-from-Sequence (`?from-sequence=<N>` and `Last-Event-ID`;
    catch-up boundary markers; HTTP 410 on out-of-retention)
  - §5.3.6 Backpressure and Buffer Overflow
  - §5.3.7 SSE Wire Format
  - §5.3.8 WebSocket Wire Format
  - §5.3.9 Rate Limiting and Quotas
- **§2 Event Envelope**: `:tenant/id` field added.

### N6 — Evidence & Provenance (0.5.0 → 0.6.0)

- **§3.1.1 Artifact Types** (G7): `:pr-context-pack` registered under External
  PR artifact types with full content schema (PR id, provider, repo, diff
  summary, metadata, CI status, review status, readiness, risk, capture
  timestamp).
- **§2.1 Evidence Bundle Structure** and **§3.1 Artifact Structure**:
  `:tenant/id` field added.

### N8 — Observability Control Interface (0.2.0 → 0.3.0)

- **§3.1.5 Checkpoint Control** (G8): `checkpoint.request`,
  `checkpoint.approve`, `checkpoint.reject` actions with parameters and
  protocol (emit `checkpoint/reached`, transition workflow to
  `:paused-awaiting-checkpoint`, expiration handling).
- **§3.1.6 Model Control** (G8): `model.override`, `model.set`, `model.clear`
  with required justification, allowed-models policy check, expiration, no
  silent fallback, no cross-workflow persistence.
- **§10 Event Stream Extensions**: new events `checkpoint/reached`,
  `checkpoint/expired`, `model/overridden`, `model/override-expired`.

### N9 — External PR Integration (0.1.0 → 0.2.0)

- **§9.1 New Artifact Types** (G7): `:pr-context-pack` registered (schema in
  N6 §3.1.1); ingestion emission obligation on PR creation and significant
  updates; immutability requirement.

### N11 — Task Capsule Isolation (0.1.0 → 0.2.0)

- **§10 TaskExecutor Protocol** (G1): hoisted from informative docs to
  normative. Defines:
  - §10.1 Protocol methods: `executor-type`, `available?`,
    `acquire-environment!`, `executor-execute!`, `copy-to!`, `copy-from!`,
    **`persist-workspace!`**, **`restore-workspace!`**, `release-environment!`
  - §10.2 Method requirements: idempotency, ordering, persistence kinds
    (`:git`, `:object-store`), workspace digest computation
  - §10.3 Executor registry and selection (no silent downgrade per N11.MD.1)
  - §10.4 Evidence emissions per lifecycle method
  - §10.5 Fleet extension point for cross-node capsule binding
- §11–§14 renumbered (was §10 Implementation Mapping → §11; §11 Conformance
  → §12; §12 New Definitions → §13; §13 References → §14).
- §1 scope listing updated to reference new §10.

### SPEC_INDEX (0.6.0 → 0.7.0)

- Top-level version bumped; new history entry summarizes the fleet enablement
  amendments and per-spec bumps.

## Testing

This PR is documentation-only. No code changes, no schema-validation tests
break (the only schema additions are OPTIONAL fields). Reviewers should
verify:

- [ ] Section numbering is consistent (especially N11 renumber)
- [ ] Cross-references between specs resolve (e.g., N6 §3.1.1
      `:pr-context-pack` schema matches N9 §9.1 description and Fleet N10 §6)
- [ ] Tenant propagation rules in N1 §2.32 are consistent with the field
      additions in N3 §2 envelope, N6 §2.1 bundle, N6 §3.1 artifact, and N1
      §2.1/§2.26 schemas
- [ ] TaskExecutor protocol method signatures are realizable (the existing
      Docker/K8s/Worktree implementations should map cleanly to the new
      protocol)

## Checklist

### Required

- [x] Documentation-only change; no test impact
- [x] Pre-commit validation passes (no `--no-verify`)
- [x] No commented-out tests
- [x] No giant functions
- [x] Functions are small and composable (n/a — docs)
- [x] New behavior has tests (n/a — docs)

### Best Practices

- [x] Code follows development guidelines (n/a — docs)
- [x] Linting passes (markdown only)
- [x] README/docs updated if needed (SPEC_INDEX bumped)
- [x] PR doc created (this file)
- [x] Focused PR (single concern: close 8 spec gaps required for Fleet MCI)

## Related

- Closes: G1–G8 in the Fleet build plan
  (`pensive-matsumoto-68bcce` worktree of `miniforge.ai`)
- Unblocks: `miniforge-fleet-specs` Wave 1+ implementation
