<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N11-delta — Capsule Runtime Adapter

**Version:** 0.1.0-draft
**Date:** 2026-04-28
**Status:** Draft
**Conformance:** MUST
**Amends:** N11 §2.3, §5, §10; N1 §11

---

## 0. Architectural Decision Record

> **Miniforge SHALL depend on an OCI-compatible local container runtime, not on
> Docker specifically. Podman SHALL be the default OSS runtime. Docker remains
> a supported configuration. Selection is data-driven via a runtime descriptor;
> the `TaskExecutor` protocol contract is unchanged.**

This decision removes Docker as an installation requirement and replaces it
with a capability surface. It does not introduce a new protocol layer above
`TaskExecutor`; the existing protocol is already CLI-agnostic and capsule-
shaped. What changes is *below* the protocol: the Docker executor becomes a
generalized **OCI-CLI executor** parameterized by a runtime descriptor.

---

## 1. Purpose and Scope

This delta defines:

- The **runtime descriptor** schema that parameterizes the OCI-CLI executor (§2)
- The **default selection algorithm** with explicit-config-first ordering (§3)
- The **capability set** a runtime MUST expose for capsule conformance (§4)
- **Image reference rules** that survive runtime swapping (§5)
- **Policy hooks** on runtime properties that this delta enables (§6)
- **Conformance and migration** from the current Docker-only path (§7)

This delta is **non-substantive** with respect to capsule semantics: §2 and §4
of N11 are unchanged. A capsule produced by Podman MUST be indistinguishable
from a capsule produced by Docker at the boundaries N11 already enforces
(filesystem, network, credentials, time, resources).

### 1.1 Non-Goals

- This delta does NOT introduce a `LocalContainerRuntime` protocol distinct
  from `TaskExecutor`. The capability surface is data, not a protocol.
- This delta does NOT make local Kubernetes (kind, k3d, minikube, Rancher
  Desktop) part of the OSS execution path. K8s remains a Fleet concern per
  N1 §5 and the `project_k8s_fleet_only` decision.
- This delta does NOT remove Docker support. Docker remains a first-class
  configured runtime.
- This delta does NOT add Docker Compose, Docker Desktop, BuildKit, or
  Docker Engine HTTP API surface area. The implementation continues to shell
  out to a single CLI.

---

## 2. Runtime Descriptor

A **runtime descriptor** is a value that fully specifies the local container
runtime used by an OCI-CLI executor. It MUST contain:

```clojure
{:runtime/kind         keyword     ; :podman | :docker | :nerdctl
 :runtime/executable   string      ; resolved CLI path, e.g. "/opt/homebrew/bin/podman"
 :runtime/version      string      ; reported by `<exe> --version`
 :runtime/rootless?    boolean     ; rootless execution available
 :runtime/capabilities #{keyword}} ; see §4
```

The descriptor MAY contain runtime-specific tuning under
`:runtime/<kind>-options`, e.g. `:runtime/podman-options
{:network "slirp4netns" :pull-policy :missing}`. Code SHALL NOT branch on
`:runtime/kind` outside the runtime adapter; all branching SHALL go through
capability lookup or runtime-options indirection.

### 2.1 Configuration Surface

A user SHALL be able to declare the runtime in `miniforge.edn` (or an
equivalent profile) under `:miniforge/runtime`:

```clojure
{:miniforge/runtime
 {:kind :podman}}
```

Or, with overrides:

```clojure
{:miniforge/runtime
 {:kind        :docker
  :executable  "/usr/local/bin/docker"}}
```

Environment override `MINIFORGE_RUNTIME=podman|docker|nerdctl` SHALL take
precedence over file configuration, per the existing repo-config / Aero
override model (`project_repo_config_profile`).

### 2.2 Image References

The execution-plan schema (`MountSchema`, `ExecutionPlanSchema` in
`components/dag-executor/src/.../execution_plan.clj`) is unchanged. However,
all default image references SHALL be **fully qualified** (e.g.
`docker.io/miniforge/task-runner-clojure@sha256:...`). Short-name references
(e.g. `miniforge/task-runner-clojure:latest`) MUST NOT appear in defaults
because Podman's short-name resolution is interactive on first run and varies
by host configuration.

---

## 3. Selection Algorithm

The OCI-CLI executor SHALL resolve its runtime descriptor in this order:

1. **Explicit configuration.** If `:miniforge/runtime/kind` is set in the
   resolved configuration, use it. If the named runtime is unavailable, the
   executor MUST fail with a structured error; it MUST NOT silently fall back.
2. **Probe order on `PATH`.** With no explicit configuration: probe `podman`,
   then `docker`, then `nerdctl`. The first runtime whose `<exe> info`
   command returns success is selected.
3. **No runtime available.** If no runtime is found, the OCI-CLI executor's
   `available?` returns `{:available? false :reason ...}`. Executor selection
   (per N11 §10) then falls through to `:worktree`.

The doctor command (`bb doctor` / `mf doctor`) SHALL report the resolved
runtime, its version, its capability set, and any runtimes detected but not
selected. When more than one runtime is available and none is configured, the
doctor command SHALL surface the choice to the user; it SHALL NOT silently
prefer one over another beyond the documented probe order.

---

## 4. Capability Set

A capsule-conformant runtime MUST advertise the following capabilities:

| Capability                    | Meaning                                                  |
|-------------------------------|----------------------------------------------------------|
| `:oci-images`                 | Pulls and runs OCI-format images                         |
| `:run`                        | `<exe> run` semantics equivalent to `docker run`         |
| `:exec`                       | `<exe> exec` into a running container                    |
| `:build`                      | Builds OCI images from a Dockerfile context              |
| `:bind-mounts`                | Bind-mounts host paths into the container                |
| `:tmpfs-mounts`               | tmpfs mounts with `nosuid,nodev` semantics               |
| `:env-vars`                   | Sets process environment from CLI flags                  |
| `:working-dir`                | Sets working directory inside the container              |
| `:user-mapping`               | Runs as a non-root UID/GID                               |
| `:resource-limits`            | CPU and memory limits enforced by the runtime            |
| `:network-modes/none`         | `--network none` disables container networking           |
| `:network-modes/bridge`       | Default bridged network with NAT                         |
| `:image-digest-pinning`       | Resolves and inspects image digests post-pull            |
| `:graceful-stop`              | Graceful stop with configurable timeout                  |

A capsule-conformant runtime SHOULD additionally advertise:

| Capability                    | Meaning                                                  |
|-------------------------------|----------------------------------------------------------|
| `:rootless`                   | Container processes run under user namespaces            |
| `:tmpfs-uid-gid-options`      | `tmpfs` mounts accept `uid=` / `gid=` mount options      |
| `:no-new-privileges`          | `no-new-privileges` security option enforced             |
| `:read-only-root`             | Read-only root filesystem with explicit RW mounts        |
| `:cap-drop-all`               | Drop all Linux capabilities, opt back in by name         |

The OCI-CLI executor MUST verify required capabilities before
`acquire-environment!` and SHALL raise a structured error naming the missing
capability if any are absent. Workflows MAY declare additional required
capabilities under `:workflow/runtime-requires`; the executor MUST honor
these requirements.

---

## 5. Image References (Normative)

- Default images in `runner-defaults.edn` and any image referenced by an
  execution plan MUST use a fully qualified reference of the form
  `<registry>/<repo>:<tag>` or `<registry>/<repo>@sha256:<digest>`.
- Image digest pinning per N6 (Evidence & Provenance) is unchanged. The
  evidence record continues to capture the resolved digest after pull.
- The OCI-CLI executor MUST NOT rely on the runtime's short-name resolution.
  Behavior diverges across runtimes; explicit references make execution
  reproducible.

---

## 6. Policy Hooks Enabled by This Delta

This delta makes the following policies expressible. They are referenced for
forward-compatibility; their normative definitions live under N4 (Policy
Compilation Contract) when authored.

- `:runtime/no-host-docker-socket` — reject mounts that bind
  `/var/run/docker.sock` (or equivalent runtime sockets) into the capsule.
  Such a mount collapses isolation and grants host container-control.
- `:runtime/require-rootless` — warn or hard-stop when the resolved runtime
  does not advertise `:rootless`. Default level: `:warn` for OSS, `:hard-stop`
  for governed Fleet workflows.
- `:runtime/restrict-host-mounts` — review or block bind mounts of host paths
  outside an allowlist (workspace, secret material, model cache).
- `:runtime/require-image-digest-pin` — reject execution plans whose
  `:image-digest` is unresolved or is a tag rather than a digest.
- `:runtime/disallow-privileged` — reject any `:privileged true` in the
  execution plan. The current schema does not surface this flag; this policy
  exists to defend the invariant.

These policies are runtime-agnostic; they read the descriptor's capability
set and the execution plan, not the runtime kind.

---

## 7. Conformance and Migration

### 7.1 Implementation Mapping

The current implementation in
`components/dag-executor/src/ai/miniforge/dag_executor/docker.clj` (lines
60–652) satisfies this delta when reorganized as follows:

- The shellout helpers (`docker-cmd`, `run-docker`, `run-docker-process`)
  generalize to take a runtime descriptor; the binary name comes from
  `:runtime/executable`. Existing callers that pass `:docker-path` map to
  `:runtime/executable` with `:kind :docker`.
- The Docker executor record becomes an **OCI-CLI executor** record holding
  the runtime descriptor. `executor-type` returns `:docker` or `:podman`
  according to `:runtime/kind`, preserving existing observability.
- Flag dialect differences (currently only `--stop-timeout`, tmpfs `uid/gid`
  options) move to a small `runtime-flags` lookup keyed by capability and
  runtime kind. Code paths SHALL prefer capability checks; `:runtime/kind`
  branches MUST be commented with the dialect difference being papered over.
- The `kubernetes` executor and the `worktree` executor are unaffected.

### 7.2 Migration

A repository whose configuration omits `:miniforge/runtime` retains its
current behavior on a host that has only Docker installed (probe order finds
Docker, executor reports `:docker` as in N1). On a host with Podman
installed and Docker absent, the OCI-CLI executor will select Podman; this
is the intended OSS default.

A repository that pins `:miniforge/runtime/kind :docker` continues to use
Docker on hosts that have it.

The doctor command MUST surface the runtime change at first execution after
upgrade so users on Podman-equipped hosts are not silently switched.

### 7.3 Testing Conformance

A runtime SHALL be considered conformant for capsule execution if it passes:

- **Capability advertisement** — the descriptor's capability set matches
  what the runtime exposes when probed.
- **Smoke suite** — `acquire-environment!`, `execute!`, `copy-to!`,
  `copy-from!`, `release-environment!`, `persist-workspace!`,
  `restore-workspace!` against a Clojure task-runner image.
- **Boundary suite** (corresponds to N11 §2.2) — filesystem isolation,
  outbound network restriction under `:network-modes/none`, credential
  ephemerality, timeout enforcement, resource-limit enforcement.

Conformance is asserted per `(runtime, OS, OS-arch)` triple. Initial OSS
support targets:

| Runtime | macOS arm64 | macOS x86_64 | Linux x86_64 | Linux arm64 | Windows |
|---------|-------------|---------------|--------------|-------------|---------|
| Podman  | Stable      | Stable        | Stable       | Stable      | Beta    |
| Docker  | Stable      | Stable        | Stable       | Stable      | Beta    |
| nerdctl | Future      | Future        | Future       | Future      | N/A     |

Windows native conformance follows the existing platform-support beta status
(`docs/platform-support.md`). The bash demo script and `bb` tasks that
currently invoke `docker` directly SHALL switch to `mf runtime run` (see §8).

---

## 8. CLI Surface

The user-facing CLI gains the following commands. Their detailed contracts
live in N5 (CLI/TUI/API). This delta defines what they MUST do.

- `mf doctor` — extends the existing platform check to report the resolved
  runtime descriptor, its capability set, and runtimes detected but not
  selected.
- `mf runtime info` — print the resolved runtime descriptor as data.
- `mf runtime run -- <args>` — pass-through invocation of the resolved
  runtime CLI for ad-hoc use, equivalent to `<exe> run <args>`. Provided so
  documentation does not need to say "install Docker" or "install Podman" —
  the user can just `mf runtime run --rm hello-world`.

The `mf runtime run` pass-through SHALL NOT be used by the workflow engine
itself. The engine continues to compose its own argument lists per
execution-plan; it does not shell through `mf runtime run`.

---

## 9. Documentation Updates

The following documents SHALL be updated as part of this delta's
implementation. None of these updates are normative on their own; they are
called out to ensure the user-facing story matches the contract.

- `readme.md` — replace any "install Docker" guidance with "install an
  OCI-compatible runtime; Podman is recommended."
- `docs/quickstart.md` — Podman-first install instructions per OS, with
  Docker noted as a supported alternative.
- `docs/platform-support.md` — add a Runtime row to the Status Matrix.
- `docs/configuration.md` — document the `:miniforge/runtime` configuration
  block.
- `agents.md` — note that the agent capsule runs under whatever runtime is
  resolved; agents have no business inspecting the runtime kind.
- `bb.edn` and `components/bb-platform` — `bb bootstrap` on macOS SHOULD
  brew-install Podman as a development convenience, parallel to the
  existing `install:clojure` / `install:poly` tasks. This is NOT a contract
  change. The contract from §3 is BYOR (bring your own OCI-compatible
  runtime); bootstrap is opt-in convenience for users who run it.
  `check:platform` SHALL treat the absence of any OCI runtime as a warning,
  not a failure, so users on Linux distro packages, Colima, OrbStack, or
  Docker-only setups are unaffected.

---

## 10. Open Questions

- **macOS performance.** Podman on macOS runs inside `podman machine`
  (a Linux VM). Bind mounts cross the VM boundary via virtiofs/9p; large
  worktrees and file-watcher events behave differently from Docker
  Desktop's virtio. Conformance MUST include a worktree-mount smoke test on
  macOS arm64 before Podman becomes the documented default.
- **Windows native.** Both Docker Desktop and Podman on Windows depend on
  WSL2 in practice. The conformance matrix labels both Beta. Whether either
  graduates to Stable depends on the existing Windows native effort
  (`docs/platform-support.md`); not a goal of this delta.
- **nerdctl/containerd.** Listed as Future. Adding nerdctl is a runtime
  descriptor plus a flag-dialect entry; the work is small but is gated on
  there being an actual user need, not on architectural readiness.
- **OrbStack.** Not a runtime; OrbStack ships its own `docker` CLI and is
  consumed via `:kind :docker` with no further work. Documented as a
  supported development convenience, not an OSS default.

---

## 11. Cross-References

- N11 §2.3 — capsule mechanisms (this delta narrows "Container (Docker,
  Podman)" to a normative descriptor).
- N11 §10 — TaskExecutor protocol (unchanged).
- N1 §11 — context tool contract (unaffected; runtime is below this layer).
- N4 — Policy Compilation Contract (the runtime policies in §6 will be
  authored against N4's policy schema).
- N6 — Evidence & Provenance (image-digest pinning is N6's responsibility;
  this delta only requires that defaults are pin-friendly).
- `docs/platform-support.md` — host platforms that miniforge supports.
