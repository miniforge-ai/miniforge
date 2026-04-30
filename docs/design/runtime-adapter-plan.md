<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Implementation Plan — Capsule Runtime Adapter (N11-delta)

**Date:** 2026-04-28
**Status:** Draft
**Spec:** [specs/normative/N11-delta-runtime-adapter.md](../../specs/normative/N11-delta-runtime-adapter.md)

This is the implementation plan for N11-delta. It sequences the work into
PR-sized phases, names the components touched, and calls out the points
where verification is non-obvious.

---

## Premise

The Docker integration is one component (`components/dag-executor`), one file
(`docker.clj`), pure CLI shellouts, no daemon API. The protocol contract
(`TaskExecutor`) is already capsule-shaped and runtime-agnostic. Phase 1 is a
small, mechanical refactor; the larger surface is documentation, doctor, and
verification on real hosts.

Estimated total effort: **3–4 PRs**, roughly 1–2 weeks elapsed including
real-host smoke testing on macOS and Linux.

---

## Phase 1 — Generalize the Docker executor (1 PR)

**Goal:** rename the Docker executor to OCI-CLI executor, parameterize it by
a runtime descriptor, ship with `:kind :docker` as the only supported value
to keep the change a pure refactor.

**Touch list:**

- `components/dag-executor/src/ai/miniforge/dag_executor/docker.clj` — split
  into:
  - `runtime/descriptor.clj` — the runtime descriptor schema, probe helpers,
    capability-set construction.
  - `runtime/oci_cli.clj` — the generalized executor (the bulk of the
    current `docker.clj`), accepting a runtime descriptor.
  - `runtime/flags.clj` — the small flag-dialect lookup keyed by capability
    and runtime kind. Initially Docker-only entries; Podman entries land in
    Phase 2.
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/executor.clj`
  — unchanged. The protocol is correct as-is.
- `components/dag-executor/src/ai/miniforge/dag_executor/factory.clj` (or
  wherever `select-executor` lives) — `:docker` continues to resolve as
  today; add a stub for `:podman` that returns "not yet supported."
- `components/dag-executor/test/.../docker_test.clj` — rename to
  `oci_cli_test.clj`. Existing assertions should pass unchanged because
  `run-docker` mocks become `run-runtime` mocks with the binary parameter.

**Verification:**

- All existing `dag-executor` tests pass.
- `projects/miniforge/e2e/.../sandbox_e2e_test.clj` still skips when Docker
  is absent and runs to completion when Docker is present.
- `bb test` is green.

**Out of scope for this PR:**

- No Podman code path yet.
- No CLI surface changes.
- No documentation changes.

---

## Phase 2 — Land Podman as a runtime (1 PR)

**Goal:** Podman becomes a fully supported runtime kind with its own
flag-dialect entries and capability advertisement. Selection still requires
explicit `:kind :podman` configuration; auto-probe is in Phase 3.

**Touch list:**

- `components/dag-executor/src/.../runtime/flags.clj` — add Podman entries.
  Differences vs Docker that must be handled:
  - tmpfs `uid=` / `gid=` mount options — Podman supports these (capability
    advertised).
  - `--stop-timeout` — supported by Podman, no change.
  - Default short-name resolution — avoided by §5 of the spec; defaults are
    fully qualified.
- `components/dag-executor/src/.../runtime/descriptor.clj` — Podman probe:
  `podman info` returns success, parse `--version`, capability detection from
  `podman info --format json`.
- `components/workflow/resources/config/.../defaults.edn` — change default
  image references to fully qualified
  (`docker.io/miniforge/task-runner-clojure@sha256:...`) per spec §2.2 and §5.
  This is a backwards-compatible change for Docker users (Docker resolves
  fully qualified references identically).
- New tests: `oci_cli_test` parameterized over `[:docker :podman]` for the
  argument-construction paths. Real-runtime conformance suite is gated
  behind a `--runtime` flag; default test run is mocked.

**Verification:**

- Mocked tests pass for both runtime kinds.
- A user can set `:miniforge/runtime/kind :podman` and a Podman host runs
  the existing e2e sandbox tests.
- A user with Docker only continues to work as before.

**Risk:**

- The fully-qualified default images require the digests to actually exist
  on `docker.io/miniforge/...`. If those images are not yet pushed with
  pinned digests, this PR includes the publish step or pins to whatever
  digest is currently `latest`.

---

## Phase 3 — Selection, doctor, CLI (1 PR)

**Goal:** automated probe order, doctor reporting, `mf runtime info` and
`mf runtime run`. This is where the user-facing default flips to Podman on
hosts that have both runtimes.

**Touch list:**

- `components/dag-executor/src/.../factory.clj` — implement the spec §3
  selection algorithm: explicit config > probe order (`podman`, `docker`,
  `nerdctl`) > unavailable.
- `bases/cli/.../doctor.clj` (or wherever `bb check:platform` lives, per
  `docs/platform-support.md` §"Verifying Your Setup") — extend to print the
  resolved runtime descriptor and the unselected runtimes.
- `bases/cli/.../runtime.clj` (new) — `mf runtime info` and `mf runtime
  run`.
- `messages/en.edn` (or equivalent i18n keys) — strings for new doctor
  output. Avoid hardcoded English per `feedback_pr_doc_localization`.
- Tests for the selection algorithm: explicit-config-overrides-probe,
  fail-loud-on-missing-explicit, probe-order-without-config.

**Verification:**

- `bb check:platform` on a host with Podman + Docker reports both, selects
  Podman, and notes that explicit config can override.
- `mf runtime run --rm hello-world` works on macOS (Podman) and Linux
  (Podman or Docker, whichever is installed).
- `mf runtime info` returns the descriptor as data.

**Acceptance demo:**

- On a Podman-only macOS host, run `examples/demo/run-demo.sh` (or its
  cross-platform replacement) end-to-end.
- On a Docker-only Linux host, run the same.

---

## Phase 4 — Docs, bootstrap, smoke matrix, runtime policies (1 PR)

**Goal:** the user-facing narrative matches the contract, `bb bootstrap`
installs a runtime on Mac as a courtesy (not a contract change), the
conformance matrix has real entries, and the policy hooks from spec §6 are
wired into the existing standards pack.

**Touch list:**

- `readme.md` — Podman-first; "OCI-compatible local runtime" framing.
- `docs/quickstart.md` — Podman install per OS; Docker as alternative.
- `docs/platform-support.md` — Runtime row in the status matrix; macOS
  bind-mount caveats called out explicitly.
- `docs/configuration.md` — `:miniforge/runtime` block.
- `agents.md` — agents do not branch on runtime kind.
- `components/bb-platform/src/.../core.clj` — owns all the actual logic.
  Per `feedback_bb_edn_thin_wrapper`, `bb.edn` cannot host non-trivial
  Clojure; it stays a thin dispatcher. Specifically:
  - Add a `manual-install-hints` entry for `podman` (Linux distro packages,
    Windows scoop) so users without brew get a useful pointer.
  - Add a `podman-machine-status` / `init-podman-machine!` pair: probe
    `podman machine list --format json`, init+start a default machine if
    none exists. Mac-specific (Podman on Linux is daemonless and needs no
    machine; Windows native is Beta and tracked separately).
  - Add a `check-runtime` function returning `{:available? :selected
    :reason}` for the OCI runtime layer. `check:platform` consumes this
    with **warn-only** semantics — absence of any OCI runtime is a warning,
    never a bootstrap failure, since Linux distro packages, Colima, OrbStack,
    and Docker-only setups must continue to work.
- `bb.edn` — three new one-line dispatchers, parallel to the existing
  `install:clojure` / `install:poly` shape. No closures, no logic, no
  `:init` additions. Concretely:
  - `install:podman` → `(brew-install "podman")`, then `(run 'setup:podman-machine)`.
  - `upgrade:podman` → `(brew-upgrade "podman")`.
  - `setup:podman-machine` → calls `platform/init-podman-machine!`.
  - Add `install:podman` to the `install:deps` `:depends` fan-out so
    `bb bootstrap` includes it on Mac. Linux uses the platform package
    manager per `docs/platform-support.md` precedent (`bb bootstrap` is
    documented as Mac-only convenience).
  - Extend the `check:platform` tool list (line 291) to include `"podman"`,
    relying on the warn-only semantics added in `bb-platform`.
- Standards pack — author the four spec §6 policy rules as MDC files
  in the `miniforge-ai/miniforge-standards` repository (consumed here
  as the `.standards/` submodule):
  `foundations/runtime-no-host-docker-socket.mdc` (`:hard-stop`),
  `foundations/runtime-require-rootless.mdc` (`:warn` OSS /
  `:hard-stop` fleet),
  `foundations/runtime-restrict-host-mounts.mdc` (`:review`),
  `foundations/runtime-require-image-digest-pin.mdc` (`:hard-stop`).
  Consumer wire-up here: bump the `.standards` submodule pointer +
  rerun `bb standards:pack` to regenerate
  `components/phase/resources/packs/miniforge-standards.pack.edn`.
- `Dockerfile.task-runner*` — rename references in build instructions to be
  runtime-neutral (`<exe> build -t ...`). The Dockerfile syntax itself is
  OCI-standard; the build command is the only thing that changes.

**Verification:**

- macOS arm64 smoke run on Podman, Linux x86_64 smoke run on Podman, both
  using bind-mounted worktrees.
- `bb bootstrap` on a fresh Mac installs Podman, initializes
  `podman machine`, and `mf doctor` reports a healthy runtime descriptor
  without further user action.
- `bb bootstrap` on a Mac that already has Docker installed but not Podman
  succeeds, installs Podman, and `mf doctor` selects Podman by probe order
  (per spec §3) while announcing the change so the Docker user is not
  surprised.
- Standards pack validates against an execution plan that mounts
  `/var/run/docker.sock` and emits the expected hard-stop policy violation.

**BYOR vs canonical-runtime tradeoff (decision recorded for posterity):**

The contract from N11-delta §3 is BYOR (bring your own OCI-compatible
runtime). `bb install:podman` is a Mac-only convenience consistent with how
`install:clojure` / `install:poly` already work — bb bootstrap on Mac
brew-installs the recommended development toolchain, but a user who already
has Docker, who is on Linux with their distro's package manager, or who
runs Colima/OrbStack is unaffected. The alternative — declaring Podman the
**canonical** runtime and requiring its presence — was considered and
rejected:

- It would force users with working Docker setups to install a second
  runtime they do not need.
- It would couple OSS adoption to a specific runtime brand we just spent
  this delta decoupling from.
- It would not actually buy anything the capability surface in spec §4
  does not already buy.

If a future need emerges (e.g. governance policies that require rootless
specifically and no other runtime in the matrix advertises `:rootless`),
the decision can be revisited. Until then: bootstrap installs Podman as a
courtesy, the contract stays BYOR.

---

## What this plan deliberately omits

- **No new protocol.** `TaskExecutor` is the right seam. Adding a
  `LocalContainerRuntime` protocol below it would be a pure tax: every
  implementation would just shell out to a CLI, which is what the executor
  already does.
- **No daemon API integration.** Both Docker and Podman expose HTTP APIs;
  using them would re-introduce the dependency we are removing. CLI
  shellout is the contract.
- **No Compose, no BuildKit-specific flags, no Docker Desktop integration.**
  None of these exist in the codebase today. The plan does not add them.
- **No local Kubernetes path.** Per `project_k8s_fleet_only`, K8s lives in
  Fleet. This plan does not bring it into OSS as a substitute for Docker.
- **No Phase 0 audit.** The audit was done while writing the spec; the
  surface area is small enough that further inventory work would be
  duplicative.

---

## Sequencing notes

- Phase 1 and Phase 2 can land back-to-back without user-visible changes.
- Phase 3 is the breaking-ish PR (the default flips on Podman-equipped
  hosts). It MUST ship with the doctor messaging that surfaces the change
  per spec §7.2.
- Phase 4 is the polish PR. It can be split if the standards-pack policy
  work needs more time than the docs.

---

## Risk Register

| Risk                                                          | Mitigation                                                                 |
|---------------------------------------------------------------|----------------------------------------------------------------------------|
| Podman macOS bind-mount perf surprises a user mid-PR-cycle    | Smoke test in Phase 4 across worktree of representative size; document    |
| Default image digests not yet published to docker.io          | Phase 2 includes the publish step or pins to known-good digest            |
| Probe order surprises users who had Docker as expected default | Doctor surfaces the change on first run after upgrade per spec §7.2       |
| nerdctl/containerd users want first-class support             | Capability set + flag-dialect lookup makes this additive; add when needed |
| Windows native fails Podman conformance                       | Beta status preserved; not a Phase-4 blocker                              |
| Existing tests assume `docker` literally on PATH              | Audited in Phase 1; tests parameterize over runtime kind                  |

---

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md)
- Parent: [N11-task-capsule-isolation](../../specs/normative/N11-task-capsule-isolation.md)
- Platform: [docs/platform-support.md](../platform-support.md)
- Memory: `project_capsule_agent_agnostic`, `project_k8s_fleet_only`,
  `project_standards_compilation_path`
