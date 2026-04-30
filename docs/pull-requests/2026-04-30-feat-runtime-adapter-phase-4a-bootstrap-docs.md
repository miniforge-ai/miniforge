# feat: runtime adapter Phase 4a — bb install:podman + docs pass

## Overview

Phase 4a of the four-phase runtime-adapter implementation plan from
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md). This PR pairs
the user-facing bootstrap convenience (`bb install:podman`) with the
documentation pass that explains the runtime story across README,
quickstart, platform support, configuration, and agents.

The standards-pack runtime policies are deliberately split into Phase 4b
to keep this PR reviewable.

## Motivation

Phase 3 landed the auto-probe + doctor + `mf runtime` CLI, but the user
has to find Podman themselves on macOS and the docs still imply Docker
is required. Phase 4a closes both gaps:

1. `bb bootstrap` on macOS now brew-installs Podman and initializes a
   default `podman machine` — same shape as the existing
   `install:clojure` / `install:poly` tasks.
2. README, quickstart, platform-support, configuration, and agents docs
   describe Podman as the recommended default and explain how to
   override.

## Changes In Detail

### 1. `bb-platform/core.clj`

- Added `podman` to `manual-install-hints` with distro-package-manager
  pointers for Linux and `scoop install podman` for Windows.
- New `init-podman-machine!` (Layer 1, side-effecting): macOS-only
  courtesy that probes `podman machine list`, then either
  - skips when not macOS (Linux Podman is daemonless),
  - skips when no Podman on PATH (caller failed earlier),
  - starts an existing machine,
  - or runs `podman machine init && podman machine start`.

Two helpers (`podman-machine-running?`, `podman-machine-exists?`) are
private; the public surface is just `init-podman-machine!`.

### 2. `bb.edn`

Three new one-line dispatchers, parallel to the existing
`install:clojure` / `install:poly` shape — no logic in `bb.edn` itself
(per `feedback_bb_edn_thin_wrapper`):

- `install:podman` → `(brew-install "podman")` then
  `(run 'setup:podman-machine)`.
- `setup:podman-machine` → `(platform/init-podman-machine!)`.
- `upgrade:podman` → `(brew-upgrade "podman")`.

`install:podman` is added to the `install:deps` `:depends` fan-out, so
`bb bootstrap` covers Podman on Mac. `upgrade:podman` is added to
`upgrade:deps`. Linux users get a hint pointing at their distro
package manager; the install command on Linux is a no-op print
(consistent with how `install:clojure` etc. behave on Linux today).

### 3. Documentation

- **README** — Prerequisites section now lists "OCI-compatible local
  container runtime (Podman recommended; Docker supported)". Bootstrap
  paragraph explains that `bb bootstrap` brew-installs Podman + inits
  the machine on macOS, points Linux users at their package manager,
  and notes the `MINIFORGE_RUNTIME=docker` override for Docker users.
- **`docs/quickstart.md`** — Mirrors the README prerequisites change.
  New "Container runtime" section with the `mf doctor` / `mf runtime
  info` / `mf runtime run --rm hello-world` triple, the auto-probe
  order, and the explicit-fail-loud rule.
- **`docs/platform-support.md`** — New "Container Runtime Matrix" with
  Podman / Docker / nerdctl rows across macOS arm64/x86_64, Linux
  x86_64/arm64, Windows. Notes call out the macOS `podman machine`
  bind-mount tradeoff, the Linux daemonless+rootless story, and the
  fact that Colima/OrbStack are Docker-CLI hosts (consume as
  `:runtime-kind :docker`), not separate runtimes.
- **`docs/configuration.md`** — New "Container runtime" section
  documenting the `:miniforge/runtime` config block (`:kind`,
  `:executable`, `:docker-path` legacy alias) and the `MINIFORGE_RUNTIME`
  env var override.
- **`agents.md`** — New "Container Runtime (N11-delta)" section with
  the four agent rules: don't branch on `:runtime/kind` in code (use
  the registry); don't add daemon-API integration; image references
  must be fully qualified; selection algorithm is settled.

### 4. Tests

- `bb-platform/core_test.clj` adds two deftest forms covering
  `install-plan` for Podman: `:macos` dispatches to brew, `:linux`
  returns a distro-package-manager hint with `apt install podman` /
  `dnf install podman` / `pacman` substrings.
- The `init-podman-machine!` side-effecting helper is intentionally not
  unit-tested — its branches are guarded by `(os-key)` and
  `(installed? "podman")`, both of which depend on host state. Phase 4b
  or a smoke pass on a fresh Mac is the right place to verify it
  end-to-end.

## What this PR does NOT change

- Standards-pack runtime policies (`:runtime/no-host-docker-socket`
  etc.) — Phase 4b.
- Pinning default images to `@sha256:` digests — follow-up once images
  are published with stable digests; the test regex in `images_test.clj`
  already accepts the digest form.
- The runtime selector / executor / registry — Phases 1–3 territory,
  untouched here.

## Files

- `bb.edn` — install:podman, setup:podman-machine, upgrade:podman tasks; added to deps fan-outs
- `components/bb-platform/src/.../core.clj` — `manual-install-hints` entry; `init-podman-machine!` helper
- `components/bb-platform/test/.../core_test.clj` — Podman install-plan tests (Mac + Linux)
- `readme.md` — Prerequisites + bootstrap paragraph updated
- `docs/quickstart.md` — Prerequisites + new Container runtime section
- `docs/platform-support.md` — new Container Runtime Matrix + per-OS notes
- `docs/configuration.md` — new Container runtime section
- `agents.md` — new Container Runtime (N11-delta) rules section

## Verification

- `bb tasks | grep podman` shows `install:podman`, `setup:podman-machine`,
  `upgrade:podman`.
- `markdownlint` clean across the five touched docs.
- `bb-platform` tests: 23 deftest forms, 58 assertions, 0 failures.
- Full `bb test`: clean.

## Open follow-ups (Phase 4b)

- Standards-pack runtime policies:
  - `:runtime/no-host-docker-socket` — reject `/var/run/docker.sock`
    bind mounts.
  - `:runtime/require-rootless` — warn / hard-stop based on the
    runtime's `:rootless` capability.
  - `:runtime/restrict-host-mounts` — review/block bind mounts of host
    paths outside an allowlist.
  - `:runtime/require-image-digest-pin` — reject execution plans whose
    `:image-digest` is a tag rather than a digest.
- Pin default images to `@sha256:` digests when published.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md)
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Phase 1: [#694](https://github.com/miniforge-ai/miniforge/pull/694),
  [#701](https://github.com/miniforge-ai/miniforge/pull/701)
- Phase 2: [#706](https://github.com/miniforge-ai/miniforge/pull/706)
- Phase 3: [#710](https://github.com/miniforge-ai/miniforge/pull/710)
