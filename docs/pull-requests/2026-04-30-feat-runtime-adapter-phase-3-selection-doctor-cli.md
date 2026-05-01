<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: runtime adapter Phase 3 — auto-probe, doctor, `mf runtime` CLI

## Overview

Phase 3 of the four-phase runtime-adapter implementation plan from
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md). This is the
**user-facing flip**: hosts with both runtimes installed will now pick
Podman by default, the doctor surfaces every probe outcome, and `mf
runtime` exposes the descriptor + a runtime-CLI pass-through.

## Motivation

Phase 1 generalized the executor; Phase 2 marked Podman supported. Both
were transparent to end users — selection still required explicit
`:runtime-kind`. Phase 3 closes the loop:

1. **Auto-probe.** Without explicit config, the selector tries
   `:podman → :docker → :nerdctl`. First whose `runtime-info` probe
   succeeds wins. Per N11-delta §3.
2. **Fail-loud on explicit config.** If a user pins `:runtime-kind`,
   selection MUST honor it. If that runtime is unavailable, surface
   the failure rather than silently falling back — the user named a
   specific runtime; defying that defeats the purpose.
3. **Doctor visibility.** `mf doctor` now lists every probed kind with
   per-kind status, names the selected runtime + version, and shows
   how to override.
4. **CLI surface.** `mf runtime info` prints the descriptor as data;
   `mf runtime run -- <args>` is a pass-through to the resolved CLI
   for ad-hoc use (`mf runtime run --rm hello-world`).

## Changes In Detail

### 1. New `runtime/selector.clj`

Implements the §3 selection algorithm. Pure-data interface — the CLI
and doctor render the result, this namespace stays string-free except
for internal-use error messages on the result map.

- `select-runtime` — single entry point. Branches on
  `(:runtime-kind config)`:
  - present → `select-explicit`. Validates the kind is `:supported?`,
    runs the probe, returns ok with the descriptor or err with
    `:runtime/explicit-unsupported` / `:runtime/explicit-unavailable`.
    Never falls back.
  - absent → `select-auto`. Walks `probe-order` (Podman, Docker,
    nerdctl) filtered by `:supported?`, returns the first kind whose
    probe succeeds, or err with `:runtime/none-available` and the
    full per-kind summary list.
- `probe-order` is a top-level def — `[:podman :docker :nerdctl]` —
  exposed for documentation and the CLI doctor.

### 2. `dag-executor.interface` — new public re-exports

The CLI consumes the runtime adapter through the public interface:

- `select-runtime`
- `runtime-probe-order`
- `runtime-known-kinds`, `runtime-supported-kinds`
- `runtime-info`, `runtime-executable`, `runtime-kind`

Existing exports (`create-docker-executor`, `task-runner-images`, etc.)
unchanged.

### 3. New CLI module `cli/main/commands/runtime.clj`

Owns user-facing rendering. No business logic — calls
`dag/select-runtime` and renders the result through the shared CLI
message catalog.

- `runtime-info-cmd` — `mf runtime info`. Prints kind / executable /
  version / selection method / per-kind probe summary, then the raw
  descriptor as EDN (so the output is grep-able and programmable).
- `runtime-run-cmd` — `mf runtime run -- <args>`. Resolves the runtime
  descriptor, then `process/process` with `:inherit true` for stdio
  pass-through. Exit code propagates. Per the spec: this command is for
  ad-hoc use; the workflow engine builds its own argv via the OCI-CLI
  executor.
- `print-doctor-runtime-section` — used by `doctor-cmd` in main.clj.
  Renders one line for the selected runtime plus the per-kind probe
  list and the override hint.

Config sourcing: `MINIFORGE_RUNTIME` env var is read here. File-config
integration (the broader `:miniforge/runtime/kind` key per N11-delta
§2.1) layers on without changing the selector contract.

### 4. `main.clj` wiring

- Import `cmd-runtime`.
- Three command-fn shims: `runtime-info-cmd`, `runtime-run-cmd`.
- Doctor extended with the runtime section between the system checks
  and the config-file check.
- Dispatch table entries:
  - `mf runtime`        → help
  - `mf runtime info`   → `runtime-info-cmd`
  - `mf runtime run`    → `runtime-run-cmd` (args after `--` forwarded)

### 5. i18n

New keys in `bases/cli/resources/config/cli/messages/en-US.edn`:

- `:runtime/doctor-section-title`
- `:runtime/doctor-line` — `Runtime: {kind} {runtime-version} (selected by {selection})`
- `:runtime/doctor-error-line`
- `:runtime/doctor-probed-line`
- `:runtime/doctor-override-hint`
- `:runtime/probed-available` / `:runtime/probed-unavailable`
- `:runtime/info-{kind,executable,runtime-version,selection,probed}`
- `:runtime/error-no-runtime`

No raw English strings in the runtime command code paths.

### 6. Tests — `selector_test.clj`

Eight tests covering the §3 algorithm:

- `auto-probe-prefers-podman-when-both-available-test`
- `auto-probe-falls-through-to-docker-test`
- `auto-probe-selects-podman-when-only-podman-test`
- `auto-probe-reports-every-kind-it-tried-test`
- `auto-probe-fails-loud-when-nothing-available-test`
- `explicit-kind-overrides-probe-order-test`
- `explicit-kind-fails-loud-when-unavailable-test`
- `explicit-kind-rejects-unsupported-kind-test`

Probe is stubbed via `with-redefs` on `descriptor/runtime-info` so the
tests don't depend on host state.

### 7. Manual smoke (this host: Docker 29.4.0, no Podman)

Auto-probe (no env var):

```text
✓ Runtime: docker 29.4.0 (selected by auto-probe)
   Probed: podman — Cannot run program "podman": ... , docker (29.4.0)
   Override with :runtime-kind in config or MINIFORGE_RUNTIME=docker|podman
```

`MINIFORGE_RUNTIME=podman mf runtime info` (Podman absent):

```text
Error: No usable container runtime: explicit-unavailable — Runtime :podman is configured but unavailable.
```

`MINIFORGE_RUNTIME=nerdctl mf runtime info` (unsupported):

```text
Error: No usable container runtime: explicit-unsupported — Runtime kind :nerdctl is not supported.
```

## What this PR does NOT change

- The `OciCliExecutor` defrecord and its CLI shellout helpers — Phase
  1/2 territory.
- The runtime registry data — Phase 2 already declared Podman supported.
- `bb install:podman` and standards-pack runtime policies — Phase 4.
- File-config integration for `:miniforge/runtime/kind` — Phase 4 docs
  pass; the selector contract already accepts the kind via its config map,
  so wiring file config into the CLI is a layered change.
- `kubernetes.clj`, `worktree.clj`, `protocols/executor.clj` — untouched.

## Files

- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/selector.clj` — new
- `components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/selector_test.clj` — new
- `components/dag-executor/src/ai/miniforge/dag_executor/interface.clj` — added `select-runtime` and
  `runtime-{probe-order,known-kinds,supported-kinds,info,executable,kind}` re-exports
- `bases/cli/src/ai/miniforge/cli/main/commands/runtime.clj` — new
- `bases/cli/src/ai/miniforge/cli/main.clj` — runtime dispatch entries, doctor extension,
  command-fn shims
- `bases/cli/resources/config/cli/messages/en-US.edn` — new `:runtime/*` keys

## Verification

- New selector tests: 8 deftest forms, 26 assertions, 0 failures.
- Full `bb test`: 2943 tests, 10945 passes, 0 failures, 0 errors.
- Manual: `mf runtime info`, `mf doctor`, `MINIFORGE_RUNTIME=podman mf
  runtime info`, `MINIFORGE_RUNTIME=nerdctl mf runtime info` all
  render correctly.

## Open follow-ups (Phase 4)

- `bb install:podman` + `bb upgrade:podman` brew tasks; `bb-platform`
  `manual-install-hints` entry; `check:platform` warn-only entry.
- README + `quickstart.md` + `platform-support.md` + `configuration.md`
  - `agents.md` updates.
- Standards-pack runtime policies:
  `:runtime/no-host-docker-socket`, `:runtime/require-rootless`,
  `:runtime/restrict-host-mounts`, `:runtime/require-image-digest-pin`.
- Pin default images to `@sha256:` digests when published.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md)
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Phase 1: [#694](https://github.com/miniforge-ai/miniforge/pull/694),
  [#701](https://github.com/miniforge-ai/miniforge/pull/701)
- Phase 2: [#706](https://github.com/miniforge-ai/miniforge/pull/706)
