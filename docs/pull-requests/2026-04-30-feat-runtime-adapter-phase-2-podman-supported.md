<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(dag-executor): runtime adapter Phase 2 — Podman as a supported runtime

## Overview

Phase 2 of the four-phase runtime-adapter implementation plan from
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md). Podman becomes
a fully supported runtime kind. Selection still requires explicit
`:miniforge/runtime/kind :podman` configuration; the auto-probe / default
flip is Phase 3.

This PR is a registry change plus the surrounding tests and docs. No new
code paths in `oci_cli.clj` — the parameterization landed in Phase 1, so
Podman support is data-driven.

## Motivation

Phase 1 left Podman behind a `:runtime/unsupported` error gate. The plan's
Phase 2 step is to flip `:supported?` for Podman in the registry, declare
its capability set + container defaults + flag dialect, and parameterize
the existing argument-construction tests over both kinds so a Podman flag
regression shows up at unit-test time.

Per N11-delta §5, default OCI image references must be **fully qualified**
to avoid Podman's interactive short-name resolution prompt on first run.
This PR also pulls image references out of `oci_cli.clj` into a new
`runtime/images.edn` config and fully-qualifies them.

## Changes In Detail

### 1. `runtime/registry.edn` — Podman flipped to supported

The `:podman` entry now has:

- `:supported? true` — `make-descriptor` accepts `:runtime-kind :podman`.
- `:capabilities` — same OCI surface as Docker, plus `:rootless`.
- `:defaults` — `:uid` / `:gid` `1000`, `:memory "512m"`, `:cpu 0.5`,
  `:tmpfs-size "512m"`. Identical to Docker today; Phase 3 (auto-probe)
  can revisit per host.
- `:flags` — `:info-format-template "{{.Version.Version}}"`. Podman's
  `info` structure differs from Docker's: server version lives under
  `.Version.Version` rather than top-level `.ServerVersion`. The dialect
  fallback in `registry/flag` would have given Podman the Docker template,
  which would silently produce `{{.ServerVersion}}` parse errors against
  the Podman info shape — declaring the override prevents that.

`:nerdctl` stays `:supported? false` (Future per the conformance matrix).

### 2. `runtime/images.edn` (new) + `runtime/images.clj` (new)

Default OCI image references are now data, not code. Three entries:

- `:default` — `docker.io/library/alpine:latest`, used when an executor
  config omits `:image`.
- `:minimal` — `docker.io/miniforge/task-runner:latest`.
- `:clojure` — `docker.io/miniforge/task-runner-clojure:latest`.

All references are fully qualified (registry + repo + tag). Pinned
`@sha256:` digests are a follow-up — the EDN structure is ready for them
the moment the images get pushed with stable digests.

`runtime/images.clj` loads the EDN once at namespace init and exposes
`default-image`, `task-runner-images` (excludes `:default`), `image`,
`dockerfile`, and `entry` accessors. Pattern mirrors `runtime/registry.clj`.

### 3. `runtime/oci_cli.clj` — consume images namespace

- Removed the top-level `default-image` constant; `create-oci-cli-executor`
  now reads from `images/default-image`.
- `task-runner-images` is now a `def` bound to `(images/task-runner-images)`
  at namespace load. Map semantics preserved for callers.
- Namespace docstring updated to describe Phase 2 state and the
  data-driven extension model.

### 4. `runtime/descriptor.clj` — error-message factories

- `format-kind-set` helper renders a set of kind keywords as a stable
  human-readable string (`:docker, :podman` rather than the raw set's
  printed form). Used by both error factories.
- `unsupported-kind-error` interpolates the live `supported-kinds` set
  into the message — no more "Phase 1 ships :docker only" copy that goes
  stale with each phase.
- `unknown-kind-error` likewise interpolates `known-kinds` via
  `format-kind-set`.

### 5. Localized message update

`config/dag-executor/runtime/messages/en-US.edn`:

```edn
:descriptor/unsupported-kind
"Runtime kind {kind} is not yet supported. Supported runtimes: {supported}."
```

The `{supported}` placeholder is filled by the live `supported-kinds`
set from the registry.

### 6. Tests

- **`oci_cli_test.clj`**:
  - `descriptor-rejects-podman-in-phase-1-test` removed.
  - `descriptor-accepts-podman-test` added — verifies the Podman
    descriptor is constructible, reports `:podman`, names `"podman"` as
    its executable, and advertises `:rootless` + `:oci-images`.
  - `descriptor-rejects-nerdctl-as-unsupported-test` added — :nerdctl
    is now the future-kind that exercises the unsupported error path.
    Also asserts that the error data carries the live `supported-kinds`
    set (containing both `:docker` and `:podman`).
  - `create-container-stop-timeout-test` parameterized over `[:docker
    :podman]` via a `doseq`; uses a new `capture-create-container-args`
    helper to keep the assertions focused on flag shape.
  - `executor-type-from-descriptor-test` parameterized similarly.

- **`registry_test.clj`**:
  - `registry-supported-kinds-phase-1-test` → `…-phase-2-test` (now
    expects `:docker` and `:podman` in the supported set).
  - `registry-supported?-test` updated for Phase 2 expectations.
  - `registry-capabilities-test` extended — Podman matches Docker's OCI
    surface and adds `:rootless`. The negative assertion confirms Docker
    does not advertise `:rootless`, which is correct.
  - New `registry-flag-podman-info-template-test` — pins the
    Podman-specific template (`{{.Version.Version}}`) and the Docker
    template (`{{.ServerVersion}}`). Catches regressions where one
    accidentally falls back to the other.
  - `registry-flag-fallback-test` updated — `:podman` no longer falls
    back to Docker for `:info-format-template` (it has its own override);
    `:nerdctl` still does.
  - `registry-default-test` extended with explicit Podman assertions.
  - `registry-tmpfs-mount-options-test` parameterized over both kinds.

- **`images_test.clj`** (new):
  - Default image and every task-runner image pass a regex that requires
    fully-qualified `<registry>/<repo>:<tag>` or `@sha256:` form per
    N11-delta §5. This test is the unit-level enforcement of the spec
    invariant.
  - `task-runner-images` excludes `:default` (it is a base image, not a
    builder).
  - `image` and `dockerfile` lookups behave per the EDN.

## What this PR does NOT change

- Auto-probe / selection algorithm / default flip — that is Phase 3.
- `mf doctor` and `mf runtime` CLI surface — Phase 3.
- `bb install:podman` and bb-platform integration — Phase 4.
- Standards-pack runtime policies (no-host-docker-socket, require-rootless,
  etc.) — Phase 4.
- `kubernetes.clj`, `worktree.clj`, `protocols/executor.clj` — untouched.

## Files

- `components/dag-executor/resources/config/dag-executor/runtime/registry.edn` — Podman entry filled in
- `components/dag-executor/resources/config/dag-executor/runtime/messages/en-US.edn` — unsupported-kind message
  generalized
- `components/dag-executor/resources/config/dag-executor/runtime/images.edn` — new (default image references, fully
  qualified)
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/images.clj` — new
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/descriptor.clj` — `format-kind-set`
  helper, error factories interpolate live registry sets
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli.clj` — consume images namespace,
  namespace docstring updated
- `components/dag-executor/src/ai/miniforge/dag_executor/executor.clj` — docstring updates for the public re-exports
- `components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli_test.clj` — Phase 2 descriptor
  tests, parameterized argument tests
- `components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/registry_test.clj` — Phase 2
  supported-set + Podman capability/flag/default tests
- `components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/images_test.clj` — new
  (fully-qualified-reference invariant)

## Verification

- New runtime suite (oci_cli + registry + images): 37 tests, 106
  assertions, 0 failures, 0 errors.
- Full `bb test`: 2935 tests, 10919 passes, 0 failures, 0 errors.
- `bb test:integration`: 0 failures, 0 errors.

## Open follow-ups

- **Pin default images to digests.** `docker.io/miniforge/task-runner-clojure@sha256:...`
  is the spec-preferred form. The EDN entries are tag-based today; once
  images are published with stable digests, swapping `:latest` → `@sha256:...`
  is a one-line per-entry edit. The `images_test.clj` regex already accepts
  both forms.
- **Phase 3** — auto-probe + `mf doctor` + `mf runtime` CLI.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md)
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Phase 1: [#694](https://github.com/miniforge-ai/miniforge/pull/694),
  [#701](https://github.com/miniforge-ai/miniforge/pull/701)
