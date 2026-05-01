<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat(dag-executor): generalize Docker executor into OCI-CLI executor (N11-delta Phase 1)

## Overview

Phase 1 of the four-phase runtime-adapter implementation plan from
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md) (PR #687). This is
a pure refactor — no behavior change. With `:runtime-kind :docker` (the
default) the executor produces identical CLI invocations and identical
container behavior to the prior Docker-only implementation.

## Motivation

The previous Docker executor was a single-runtime implementation: every
shellout took a `docker-path` string, the defrecord was named
`DockerExecutor`, and `executor-type` returned `:docker` literally. To land
Podman in Phase 2 without a second copy of the file, this PR introduces a
small **runtime descriptor** below the `TaskExecutor` protocol and
parameterizes the executor with it. The protocol contract is unchanged.

## Changes In Detail

### 1. New `runtime/descriptor.clj`

Defines the runtime descriptor schema from N11-delta §2:

```clojure
{:runtime/kind         :docker
 :runtime/executable   "docker"
 :runtime/version      nil          ; populated by runtime-info probe
 :runtime/rootless?    false
 :runtime/capabilities #{...}}
```

`make-descriptor` accepts a config map (`:runtime-kind`, `:executable`,
plus the legacy `:docker-path` alias) and returns a descriptor.
`runtime-info` probes the runtime via `<exe> info --format <template>` for
availability and version, replacing the old `docker-info`.

Phase 1 supports only `:docker`. Calling `make-descriptor` with
`:runtime-kind :podman` throws `{:runtime/unsupported :podman}` with a
message pointing to Phase 2; calling it with an unknown kind throws
`{:runtime/unknown-kind ...}`. This satisfies the plan's Phase 1 stub
requirement: a `:podman` config produces a clear "not yet supported"
error rather than silently falling through.

### 2. New `runtime/flags.clj`

Per-runtime flag-dialect lookup. Phase 1 ships only Docker entries
(`:info-format-template`, `:tmpfs-mount-options`); Phase 2 adds Podman
entries side-by-side. This is the seam that lets the executor avoid
branching on `:runtime/kind` inline.

### 3. New `runtime/oci_cli.clj` (was `docker.clj`)

Bulk of the prior implementation, parameterized by descriptor:

- `run-docker` → `run-runtime` (takes a descriptor; calls
  `descriptor/executable` to resolve the binary).
- `run-docker-process` → `run-runtime-process`.
- `docker-cmd` → `runtime-cmd`.
- `docker-info` removed (now lives as `descriptor/runtime-info`).
- `DockerExecutor` defrecord → `OciCliExecutor`. Holds the descriptor.
  `executor-type` returns `(descriptor/kind descriptor)` — `:docker` in
  Phase 1, `:podman` in Phase 2. Existing observability is preserved
  because `:docker` is still what gets emitted today.
- `build-runtime-fs-args` reads its tmpfs option string from
  `flags/flag` rather than hardcoding it. The Docker dialect entry is
  byte-identical to the old hardcoded string, so the rendered argument
  is unchanged.
- Workspace-bootstrap helpers (`infer-host-kind`, `resolve-git-token`,
  `authenticated-https-url`, `bootstrap-workspace!`) move with the rest
  of the file. They now take a descriptor instead of a `docker-path`,
  but their behavior is unchanged.
- `task-runner-images`, `find-dockerfile-path`, `image-exists?`,
  `image-repo-digest`, `build-image!`, `ensure-image!`,
  `ensure-all-images!` are preserved with descriptor-first signatures.
- Two factory functions: `create-oci-cli-executor` (the generic form)
  and `create-docker-executor` (a thin shim that calls the generic form
  with `{:runtime-kind :docker}`). The latter is kept for back-compat
  with all existing callers in the project.

### 4. Renamed test `runtime/oci_cli_test.clj` (was `docker_test.clj`)

Same coverage; mocks now redef `oci-cli/run-runtime` and
`oci-cli/exec-in-container` rather than `docker/run-docker` /
`docker/exec-in-container`. Test fixtures construct a default Docker
descriptor via `(descriptor/make-descriptor {})`. New tests cover:

- Descriptor defaults to `:docker`.
- Descriptor honors explicit `:executable` and the legacy `:docker-path`
  alias.
- Descriptor rejects `:podman` in Phase 1 with `:runtime/unsupported`.
- Descriptor rejects unknown kinds with `:runtime/unknown-kind`.
- `OciCliExecutor` reports its descriptor's kind via
  `proto/executor-type`.

Existing tests for token sanitization, GitHub/GitLab authenticated URLs,
container image-digest extraction, persist/restore-workspace, and the
N11 §2.2 `--stop-timeout` argument are carried over unchanged in
intent. All 20 deftest forms pass: 32 assertions, 0 failures, 0 errors.

### 5. `executor.clj` rewires its require

The aggregator namespace now requires
`protocols.impl.runtime.descriptor` and
`protocols.impl.runtime.oci-cli`. All public re-exports
(`create-docker-executor`, `task-runner-images`, `image-exists?`,
`build-image!`, `ensure-image!`, `ensure-all-images!`,
`prepare-docker-executor!`) keep their names. External callers in
`bases/cli/.../sandbox.clj`, `projects/miniforge/test/.../executor_test.clj`,
and `projects/miniforge/e2e/.../sandbox_e2e_test.clj` are unaffected.

`prepare-docker-executor!` now constructs a descriptor up front
(`{:runtime-kind :docker}`) and threads it into `ensure-image!`. This is
a fix-as-you-go for the previous code, which passed a `docker-path`
string into image-side helpers — that path was lossy under the new
descriptor-first signatures.

A new `create-oci-cli-executor` re-export is added for callers that
want to specify a runtime kind explicitly. Phase 2 will be the first
real consumer.

### 6. Cherry-picked `fix web dashboard approval handler race` (drop on rebase)

This branch contains commit `a90b2f31`, cherry-picked from
[#692](https://github.com/miniforge-ai/miniforge/pull/692). That fix is
unrelated to runtime-adapter work but is required for `bb pre-commit`
to pass on this branch: the changed-bricks test runner picks up
web-dashboard tests transitively when `dag-executor` changes, and those
tests currently NPE on a `requiring-resolve` soft-dep race in
`web_dashboard.server.handlers/from-exception` (line 40). Once #692
merges to main, this commit will disappear cleanly during rebase.

## What this PR does NOT change

- `protocols/executor.clj` — the `TaskExecutor` protocol is unchanged.
- `kubernetes.clj` and `worktree.clj` — both implementations are
  untouched.
- `executor_test.clj` and `sandbox_e2e_test.clj` — external integration
  tests that consume the public `executor` re-exports. No changes
  required.
- The N11-delta selection algorithm (auto-probe, doctor messaging,
  default flip from Docker to Podman) — that lands in Phase 3.
- `bb.edn` and `components/bb-platform` — Podman bootstrap install
  lands in Phase 4.
- Any user-facing docs (`readme.md`, `quickstart.md`,
  `platform-support.md`, `configuration.md`, `agents.md`) — Phase 4.

## Files

- `components/dag-executor/src/ai/miniforge/dag_executor/executor.clj` — rewired requires + `prepare-docker-executor!`
  updated
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/descriptor.clj` — new
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/flags.clj` — new
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli.clj` — renamed from
  `protocols/impl/docker.clj`, descriptor-parameterized
- `components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli_test.clj` — renamed from
  `protocols/impl/docker_test.clj`, mocks updated, new descriptor-construction tests
- (cherry-picked, drops on rebase)
  - `components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj`
  - `components/web-dashboard/test/ai/miniforge/web_dashboard/server/handlers_approval_test.clj`
  - `docs/pull-requests/2026-04-29-fix-web-dashboard-approval-handler-soft-dep-race.md`

## Verification

- `bb test` — 2496 tests, 0 failures, 0 errors (with cherry-pick of
  #692 in place).
- New tests in `oci_cli_test.clj` direct invocation: 20 tests, 32
  assertions, 0 failures, 0 errors.
- All previously-existing assertions in `docker_test.clj` carry over
  intact.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md)
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Cherry-picked: [#692](https://github.com/miniforge-ai/miniforge/pull/692)
- Memory references: `project_capsule_agent_agnostic`,
  `project_k8s_fleet_only`, `feedback_avoid_requiring_resolve`
