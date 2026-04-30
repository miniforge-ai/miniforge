# feat: runtime adapter Phase 4b — author runtime policies (MDC)

## Overview

Phase 4b — final phase of the runtime-adapter rollout per
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md). Authors the
four runtime policies from N11-delta §6 as MDC files in canonical
format, ready for compilation into the active standards pack once the
pack-migration blocker is resolved.

This PR is intentionally narrow: four MDC files plus a plan-doc note.

## Motivation

N11-delta §6 enumerates four policies that the runtime descriptor +
execution-plan boundary makes possible:

1. `:runtime/no-host-docker-socket` — reject bind mounts of host
   container sockets.
2. `:runtime/require-rootless` — warn / hard-stop based on the
   runtime's `:rootless` capability.
3. `:runtime/restrict-host-mounts` — review/block bind mounts of host
   paths outside an allowlist.
4. `:runtime/require-image-digest-pin` — reject execution plans whose
   `:image-digest` is a tag rather than a digest.

These are runtime-agnostic — they read the descriptor's capability set
and the execution plan, not `:runtime/kind`. The data model that makes
them possible already shipped in Phase 1; what was left is the policy
text and the enforcement gate.

The standards-pack compilation pipeline (`bb standards:pack`) currently
reads from `.standards/` which is an unpopulated submodule on this
checkout — per `project_standards_compilation_path`, the submodule's
removal/migration is in flight. So the compilation step is deferred,
but the **rule content** is authored, reviewable, and lands in the
right canonical location (`.cursor/rules/foundations/runtime/`).

## Changes In Detail

### 1. Four MDC files under `.cursor/rules/foundations/runtime/`

Each file follows the existing MDC format (YAML frontmatter + markdown
body) used by `.cursor/rules/foundations/stratified-design.mdc` and the
other foundation rules. Frontmatter declares dewey number,
description, and `alwaysApply: true`. Body covers detection,
enforcement (action + phases), the rationale, and spec references.

| File | Dewey | Action | Notes |
|------|-------|--------|-------|
| `no-host-docker-socket.mdc` | 030 | `:hard-stop` | No opt-out; matches `*.sock` patterns plus the canonical socket paths. |
| `require-rootless.mdc` | 031 | `:warn` (OSS) / `:hard-stop` (fleet) | Reads `:runtime/capabilities` from the descriptor — pure data check. |
| `restrict-host-mounts.mdc` | 032 | `:review` | Allowlist (workspace, secret mounts, model cache). Mounts outside allowlist surface to a human via control-plane decision card. |
| `require-image-digest-pin.mdc` | 033 | `:hard-stop` | Pattern: `sha256:<64-hex>` passes; tag-like or other non-digest values in `:image-digest` violate. |

Each rule explicitly cites the spec section (N11-delta §6, plus the
relevant N11/N6 cross-references) and explains the threat model for
its existence.

### 2. `docs/design/runtime-adapter-plan.md` — Phase 4 entry updated

The Standards-pack bullet under Phase 4's touch list now reflects what
landed: MDC files in canonical format, ready for compilation. Notes
that the compilation step is gated on the standards-pack migration.

## Why this PR is small

The plan-original Phase 4 was a single PR covering bootstrap, docs,
smoke matrix, and policies. PR #711 split it into 4a (bootstrap +
docs) for reviewability. 4b is the polish PR — small enough that the
reviewer can read the policy text in one sitting and weigh in on
threat-model framing without wading through 700 lines of code.

## What this PR does NOT change

- Does not compile the rules into
  `components/phase/resources/packs/miniforge-standards.pack.edn`.
  That step requires the `.standards/` submodule to be populated or
  the compiler to be re-pointed; both are out of scope here per
  `project_standards_compilation_path`. The MDC files are valid
  source ready for the next compile.
- Does not implement enforcement at the executor level. Enforcement
  is the standards-pack scanner's job; once the rules compile in,
  `bb review` and the in-workflow scanner pick them up automatically.
- Does not pin default images to `@sha256:` digests in
  `runtime/images.edn`. The `require-image-digest-pin` rule is about
  execution-plan `:image-digest` (resolved at plan-build time), not
  the EDN defaults. Pinning the EDN defaults is a separate, gated
  follow-up.
- Does not touch the existing 23 compiled rules in the standards pack.

## Files

- `.cursor/rules/foundations/runtime/no-host-docker-socket.mdc` — new
- `.cursor/rules/foundations/runtime/require-rootless.mdc` — new
- `.cursor/rules/foundations/runtime/restrict-host-mounts.mdc` — new
- `.cursor/rules/foundations/runtime/require-image-digest-pin.mdc` — new
- `docs/design/runtime-adapter-plan.md` — Phase 4 standards-pack bullet
  updated to reflect MDC authoring + migration gate

## Verification

- `markdownlint` clean on all four MDC files (YAML frontmatter parses,
  markdown body has no MD violations).
- Each file is a self-contained, reviewable policy artifact.

## Phase ladder — final state

- ✅ Phase 1 — OCI-CLI executor refactor (#687, #694, #701)
- ✅ Phase 2 — Podman as a supported runtime (#706)
- ✅ Phase 3 — auto-probe + doctor + `mf runtime` CLI (#710)
- ✅ Phase 4a — `bb install:podman` + docs pass (#711)
- ✅ Phase 4b — runtime policies authored (this PR)

Open follow-ups (not gated on the runtime adapter):

- Standards-pack migration unblock — when complete, `bb standards:pack`
  picks up the four new MDC files and they compile into the active
  pack alongside the existing 23 rules.
- Pin default images in `runtime/images.edn` to `@sha256:` digests
  when `docker.io/miniforge/task-runner*` are published with stable
  digests.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md) §6
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Phase 1: [#694](https://github.com/miniforge-ai/miniforge/pull/694),
  [#701](https://github.com/miniforge-ai/miniforge/pull/701)
- Phase 2: [#706](https://github.com/miniforge-ai/miniforge/pull/706)
- Phase 3: [#710](https://github.com/miniforge-ai/miniforge/pull/710)
- Phase 4a: [#711](https://github.com/miniforge-ai/miniforge/pull/711)
- Memory references: `project_standards_compilation_path`
