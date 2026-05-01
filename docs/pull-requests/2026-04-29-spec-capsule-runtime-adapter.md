<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# spec: capsule runtime adapter (N11-delta) — Podman default, Docker compatible

## Overview

This is a docs-only PR that lands a normative spec delta and a corresponding
implementation plan. It does not change any code paths.

The delta amends N11 (Task Capsule Isolation) so that miniforge depends on
an OCI-compatible local container runtime via a small *runtime descriptor*
rather than on Docker specifically. Podman becomes the default OSS runtime;
Docker remains a first-class supported configuration; nerdctl is reserved as
future-additive. The `TaskExecutor` protocol is unchanged — the surgery is
below the protocol.

## Motivation

The current OSS execution path requires Docker. That couples adoption to
Docker Desktop's licensing, daemon model, and product surface area. The
capsule contract from N11 already describes Podman as a satisfying substrate
in §2.3, but the implementation defaults to Docker and has no abstraction
for swapping it.

The good news from the audit: the existing Docker integration is much
smaller than its product footprint suggests. One component, one file, pure
CLI shellouts. No daemon API, no Compose, no Desktop integration, no
BuildKit-specific flags. The `TaskExecutor` protocol is already capsule-
shaped, and it already has Docker, Kubernetes, and worktree implementations
side-by-side.

The right shape, then, is to parameterize the existing executor with a
runtime descriptor (data) rather than introduce a new protocol layer. This
spec delta defines that descriptor and the surrounding contract; the plan
sequences the implementation across roughly four PRs.

## Changes In Detail

### 1. `specs/normative/N11-delta-runtime-adapter.md` (new)

Normative delta that amends N11 §2.3, §5, §10 and N1 §11. Defines:

- The **runtime descriptor** schema (`:runtime/kind`, `:runtime/executable`,
  `:runtime/version`, `:runtime/rootless?`, `:runtime/capabilities`).
- The **selection algorithm**: explicit configuration first, then probe
  order (`podman` → `docker` → `nerdctl`), then unavailable. Explicit
  configuration MUST NOT silently fall back.
- The **capability set** required for capsule conformance, separated into
  MUST and SHOULD tiers, expressed as data so policies can read it without
  branching on runtime kind.
- **Image reference rules**: defaults are fully qualified
  (`docker.io/...@sha256:...`) so Podman's interactive short-name resolution
  does not surprise first-run users.
- **Policy hooks** enabled by the descriptor — `:runtime/no-host-docker-socket`,
  `:runtime/require-rootless`, `:runtime/restrict-host-mounts`,
  `:runtime/require-image-digest-pin` — referenced for forward-compatibility
  with N4. Definitions land alongside the implementation in plan Phase 4.
- **Conformance and migration**: how the existing Docker-only path maps onto
  the new descriptor, what the doctor command must surface, and the initial
  conformance matrix (Podman + Docker stable on macOS arm64 / x86_64 and
  Linux x86_64 / arm64; Beta on Windows; nerdctl Future).
- **Non-goals** are explicit: no `LocalContainerRuntime` protocol
  duplicating `TaskExecutor`, no Docker daemon HTTP API, no Compose, no
  Docker Desktop integration, no local Kubernetes path, no removal of
  Docker support.
- A `bb bootstrap` note makes clear that any brew installation of Podman is
  a Mac-only **convenience**, not a contract change. The contract from §3
  is BYOR (bring your own OCI-compatible runtime). `check:platform` SHALL
  warn on missing runtime, never fail bootstrap.

### 2. `docs/design/runtime-adapter-plan.md` (new)

Implementation plan sequenced into four PRs:

- **Phase 1** — generalize the Docker executor into an OCI-CLI executor
  parameterized by a runtime descriptor. Pure refactor; ships with
  `:kind :docker` only. `components/dag-executor` reorganizes
  `docker.clj` into `runtime/descriptor.clj`, `runtime/oci_cli.clj`, and
  `runtime/flags.clj`. The `TaskExecutor` protocol is unchanged.
- **Phase 2** — land Podman behind explicit `:miniforge/runtime/kind
  :podman`. Adds Podman flag-dialect entries to `runtime/flags.clj`,
  Podman probe + capability detection to `runtime/descriptor.clj`, and
  fully qualified default image references to
  `runner-defaults.edn`.
- **Phase 3** — flip the default on probe order, add the doctor surface
  (`bb check:platform`), and introduce `mf runtime info` and
  `mf runtime run`. Includes the messaging that surfaces the default flip
  to existing Docker users so the change is not silent.
- **Phase 4** — docs (`readme.md`, `quickstart.md`, `platform-support.md`,
  `configuration.md`, `agents.md`), bb bootstrap (`install:podman`,
  `upgrade:podman`, `setup:podman-machine` as one-line dispatchers in
  `bb.edn`, with all logic in `components/bb-platform/src/.../core.clj`
  per `feedback_bb_edn_thin_wrapper`), the smoke matrix on macOS arm64
  and Linux x86_64 with bind-mounted worktrees, and the standards-pack
  policy rules from spec §6.

The plan calls out a Risk Register including macOS bind-mount perf under
`podman machine`, default-image digest publication, the surprise factor of
flipping the default on probe order, and tests that assume `docker`
literally on PATH.

It also records a **BYOR vs canonical-runtime tradeoff** decision: bootstrap
installs Podman as Mac convenience, contract stays BYOR, canonical-runtime
alternative considered and rejected because it would force a second-runtime
install on Docker users and re-couple OSS adoption to one brand we just
spent this delta decoupling from. Decision is revisitable when a future
policy demands a runtime-specific capability.

## What this PR does NOT change

- No code in `components/dag-executor`. Phase 1 is the first code PR and is
  out of scope here.
- No `bb.edn` or `components/bb-platform` changes. Phase 4 implements them.
- No README, quickstart, platform-support, or configuration doc edits.
  Phase 4 implements them.
- No new policy rules in the standards pack. Phase 4 lands them alongside
  the policy hooks defined in spec §6.

## Files

- `specs/normative/N11-delta-runtime-adapter.md` — new spec delta.
- `docs/design/runtime-adapter-plan.md` — new implementation plan.

## Cross-references

- Parent spec: [N11-task-capsule-isolation](../../specs/normative/N11-task-capsule-isolation.md)
- Spec index: [SPEC_INDEX.md](../../specs/SPEC_INDEX.md) (entry for
  N11-delta to be added when the index is next refreshed)
- Platform: [docs/platform-support.md](../platform-support.md)
- Memory references: `project_capsule_agent_agnostic`,
  `project_k8s_fleet_only`, `project_standards_compilation_path`,
  `feedback_bb_edn_thin_wrapper`
