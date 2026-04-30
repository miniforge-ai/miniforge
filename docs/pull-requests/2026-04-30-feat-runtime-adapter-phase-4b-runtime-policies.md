# feat: runtime adapter Phase 4b — runtime policies via .standards submodule

## Overview

Phase 4b — final phase of the runtime-adapter rollout per
[N11-delta](../../specs/normative/N11-delta-runtime-adapter.md) and the
[runtime-adapter-plan](../design/runtime-adapter-plan.md). Lands the
four runtime policies from N11-delta §6 by adding them to the
`miniforge-ai/miniforge-standards` submodule (the canonical source of
truth for shared engineering rules), bumping the `.standards` pointer
in this repo to that branch, and recompiling
`miniforge-standards.pack.edn` so the active scanner picks them up.

The MDC sources landed in
[miniforge-standards#14](https://github.com/miniforge-ai/miniforge-standards/pull/14)
(merged); this PR is the consumer wire-up.

## Motivation

N11-delta §6 enumerates four policies that the runtime descriptor +
execution-plan boundary makes possible:

1. `:runtime/no-host-docker-socket` — reject bind mounts of host
   container sockets.
2. `:runtime/require-rootless` — warn / hard-stop based on the
   runtime's `:rootless` capability.
3. `:runtime/restrict-host-mounts` — review host bind mounts outside
   an allowlist.
4. `:runtime/require-image-digest-pin` — reject execution plans whose
   `:image-digest` is not a `sha256:<64-hex>` content digest.

These are runtime-agnostic — they read the descriptor's capability set
and the execution plan, not `:runtime/kind`. The data model that makes
them possible shipped in Phase 1; what was left is the policy text and
the active-pack registration.

## Architecture: where rules actually live

Engineering rules in this org are stored in the
[`miniforge-ai/miniforge-standards`](https://github.com/miniforge-ai/miniforge-standards)
repository, consumed here as a git submodule at `.standards/`. The
compiler at `tasks/compile_standards.clj` (invoked by `bb standards:pack`)
walks `.standards/` and writes
`components/phase/resources/packs/miniforge-standards.pack.edn`, which
is the artifact the workflow scanner loads at runtime.

So adding a rule is a two-step:

1. Land the MDC source in `miniforge-standards`. (Done: PR #14.)
2. Bump `.standards` here to the new commit and rerun `bb standards:pack`.

This PR is the second step.

## Changes In Detail

### 1. `.standards` submodule pointer bumped

Points at `miniforge-standards` main at
[`164d143`](https://github.com/miniforge-ai/miniforge-standards/commit/164d143)
— the merge commit for #14.

### 2. `components/phase/resources/packs/miniforge-standards.pack.edn` regenerated

`bb standards:pack` recompiled the pack. New count: 39 rules (up from
35). The four new rule IDs are visible at the top level of the pack:

```text
:std/runtime-no-host-docker-socket       (dewey 030, :hard-stop)
:std/runtime-require-rootless            (dewey 031, :warn / :hard-stop)
:std/runtime-restrict-host-mounts        (dewey 032, :review)
:std/runtime-require-image-digest-pin    (dewey 033, :hard-stop)
```

| Dewey | Rule | Action | Notes |
|-------|------|--------|-------|
| 030 | `runtime-no-host-docker-socket` | `:hard-stop` | No opt-out; matches `*.sock` patterns plus the canonical socket paths. |
| 031 | `runtime-require-rootless` | `:warn` (OSS) / `:hard-stop` (fleet) | Reads `:runtime/capabilities` from the descriptor — pure data check. |
| 032 | `runtime-restrict-host-mounts` | `:review` | Allowlist (workspace, secret mounts, model cache). Mounts outside the allowlist surface to a human via control-plane decision card. |
| 033 | `runtime-require-image-digest-pin` | `:hard-stop` | Pattern: `sha256:<64-hex>` passes; tag-like or other non-digest values in `:image-digest` violate. |

### 3. Earlier mistake reverted

A previous iteration of this PR added the MDC files under
`.cursor/rules/foundations/runtime/` because the `.standards/`
submodule was unpopulated locally and I incorrectly assumed the
standards weren't consumed from there. Initializing the submodule
(`git submodule init && git submodule update`) showed the standards
repo is alive and consumed exactly as designed. The orphan
`.cursor/rules/foundations/runtime/` tree is removed in this PR;
`.cursor/rules/index.mdc` and `.gitignore` revert to their main state.

### 4. `docs/design/runtime-adapter-plan.md` — Phase 4 entry updated

The Standards-pack bullet under Phase 4's touch list now reflects
where rules actually live (`.standards/foundations/runtime-*.mdc`) and
that the consumer step is the submodule bump + `bb standards:pack`
regen.

## Files

- `.standards` — submodule pointer bumped to `miniforge-standards` main (commit `164d143`)
- `components/phase/resources/packs/miniforge-standards.pack.edn` — regenerated; 39 rules
- `.cursor/rules/foundations/runtime/*.mdc` — removed (the orphan tree)
- `.cursor/rules/index.mdc` — reverted to main
- `.gitignore` — reverted to main
- `docs/design/runtime-adapter-plan.md` — Phase 4 standards-pack bullet updated
- `docs/pull-requests/2026-04-30-feat-runtime-adapter-phase-4b-runtime-policies.md` — this doc

## Verification

- Standards repo PR ([miniforge-standards#14](https://github.com/miniforge-ai/miniforge-standards/pull/14))
  lands the four MDC sources; `markdownlint` clean on all four.
- `bb standards:pack`: "Compiled 39 rules. Failed: 0."
- `grep -o ':id [^,}]*runtime' miniforge-standards.pack.edn` shows all four runtime IDs present.

## Phase ladder — final state

- ✅ Phase 1 — OCI-CLI executor refactor (#694, #701)
- ✅ Phase 2 — Podman as a supported runtime (#706)
- ✅ Phase 3 — auto-probe + doctor + `mf runtime` CLI (#710)
- ✅ Phase 4a — `bb install:podman` + docs pass (#711)
- ✅ Phase 4b — runtime policies in the standards pack (this PR + miniforge-standards#14)

## Open follow-ups (not gated on the runtime adapter)

- Pin default images in `runtime/images.edn` to `@sha256:` digests
  when `docker.io/miniforge/task-runner*` are published with stable
  digests.

## Cross-references

- Spec: [N11-delta-runtime-adapter](../../specs/normative/N11-delta-runtime-adapter.md) §6
- Plan: [runtime-adapter-plan](../design/runtime-adapter-plan.md)
- Standards PR: [miniforge-standards#14](https://github.com/miniforge-ai/miniforge-standards/pull/14)
- Phase 1: [#694](https://github.com/miniforge-ai/miniforge/pull/694),
  [#701](https://github.com/miniforge-ai/miniforge/pull/701)
- Phase 2: [#706](https://github.com/miniforge-ai/miniforge/pull/706)
- Phase 3: [#710](https://github.com/miniforge-ai/miniforge/pull/710)
- Phase 4a: [#711](https://github.com/miniforge-ai/miniforge/pull/711)
