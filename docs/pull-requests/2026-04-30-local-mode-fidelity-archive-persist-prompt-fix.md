# feat: local-mode fidelity — archive persist, tool-first delivery, harvest CLI

## Overview

Closes the gap where dogfooding miniforge in `:local` mode silently destroyed task work at scratch-worktree teardown,
and surfaced multiple smaller bugs along the way. After this PR:

- **Workflow runs end-to-end on the local tier.** Plan + DAG tasks complete through to release without losing the
  agent's output.
- **Per-task work is preserved as a real git bundle** under `~/.miniforge/checkpoints/<workflow-id>/<task-id>.bundle`,
  so every successful task leaves an inspectable, fetchable archive even if the workflow later fails.
- **Recovery is one CLI call:** `bb harvest <workflow-id>` pulls the archives back into the host repo as named branches.
- **`:workspace/persisted` is a first-class event,** flowing through the event stream into the evidence bundle and
  rendered in the CLI display.
- **The implementer prompt no longer contradicts itself,** mandating tool-based file delivery rather than allowing a
  model to "deliver" by emitting an EDN map in chat.
- **Two pre-existing fail-closed bugs were unblocked** along the way: `bb miniforge` couldn't load namespaces because
  the classpath was missing `components/anomaly`, and the `:format` gate's `check-format` returned a `response/success`
  shape instead of `{:passed? true}`, so every implement phase tripped a spurious gate failure.

## Motivation

Dogfooding the new generalized-classification-runtime spec on the local tier exposed a fidelity gap between `:governed`
and `:local` modes that violated the project's "don't destroy user work on failure" principle (N11 §7.4):

1. The worktree executor's `persist-workspace!` was a no-op, with the comment *"Worktree files are already on host — no
  persistence needed."*
2. `persist-workspace-at-phase-boundary!` was guarded on `:execution/mode :governed`, so even if the worktree
  implementation worked, local mode never invoked it.
3. `release-environment!` then ran `git worktree remove --force` on the scratch worktree.

Combined: every task's work was destroyed at the end of execution. A workflow that ran 6 implementations and produced
~3000 lines of code ended with zero files on the host worktree.

This PR makes `:local` mode match `:governed` in not destroying work. The persistence model differs (local uses `git
bundle` to a file under `~/.miniforge/checkpoints/`, governed uses `git push` to a remote), but both tiers now leave a
recoverable artifact behind.

## Changes In Detail

### 1. Worktree-tier archive persist (`components/dag-executor`)

- `archive-bundle!` — stages dirty paths, commits on the task branch with `--no-verify` (commit is internal
  infrastructure; validation is the gate's job upstream), and writes `git bundle create <bundle-path> <branch> --not
  <base-ref>`. The `--not <base-ref>` form is essential: the alternative `<base>..HEAD` form bundles the same commits
  but only records HEAD as a head, leaving `git fetch <bundle> <branch>:<branch>` unable to resolve the branch by name.
- `restore-from-bundle!` — `git -C <host-repo> fetch <bundle> <branch>:<branch>` brings the branch and its objects into
  the host repo's object DB. The orchestrator can then check the branch out wherever it wants without touching the live
  worktree.
- Default archive root is `~/.miniforge/checkpoints/`, namespaced by `workflow-id` so concurrent runs don't collide on
  the same `task-id` stem. Override via `:archive-dir` opt or the `:archive-dir` config field.
- `acquire-environment!` now stores `:base-branch` in the env-record's `:metadata` so persist can bundle the right
  `<base-ref>..HEAD` range without guessing.
- `persist-workspace!` is now the real implementation; `restore-workspace!` calls `restore-from-bundle!`.

Tests added:

- `persist-workspace-clean-worktree-no-changes-test`
- `persist-workspace-dirty-bundles-test`
- `restore-workspace-fetches-from-bundle-test`
- `archive-bundle-fails-cleanly-on-bundle-error-test`

The two pre-existing `persist-workspace-noop-test` / `restore-workspace-noop-test` were rewritten to assert the new
contract; they previously asserted the buggy no-op behavior.

### 2. Wire `:local` mode to persist (`components/workflow`)

`persist-workspace-at-phase-boundary!` no longer gates on `:execution/mode :governed`. Both modes participate. The
dispatched implementation differs by tier — docker/k8s pushes to a remote via `git-persist!`; worktree writes a bundle
via `archive-bundle!`.

The call site threads `:base-branch` from `:execution/environment-metadata` into the persist opts so the worktree tier
can bundle the right range.

`persist-workspace-noop-when-not-governed-test` was renamed to `persist-workspace-fires-in-local-mode-test` and its
assertions inverted.

### 3. `:workspace/persisted` first-class event (`components/event-stream`, `bases/cli`)

Promotes the previous `log/info` line to a real event so the dashboard and evidence bundle have a structured handle on
persistence.

- `events/workspace-persisted` constructor in `event-stream/core.clj`. Carries `:workspace/phase`, `:workspace/env-id`,
  `:workspace/branch`, `:workspace/commit-sha`, `:workspace/bundle-path`, `:workspace/tier`.
- Registered in `event_type_registry` and `default-event-privacy` (`:public` — bundle paths are user-facing and contain
  no secrets).
- `WorkspacePersisted` Malli schema added.
- Re-exported through `interface.clj` and `interface/events.clj`.
- `runner_environment.clj` publishes the event in addition to logging it. Falls back silently if no `:event-stream` is
  on context (keeps the persist path robust in test contexts).
- CLI display renders `↳ Persisted (<phase>): <bundle-path>` so the user sees checkpoint creation in real time.

### 4. Implementer prompt: tools are delivery (`components/agent`)

The previous prompt had a contradiction:

- *"Your FIRST action must be writing code (Write/Edit) — NOT reading files."*
- *"You must output a structured code artifact as a Clojure map: `{:code/files [...]}`"*

A model could satisfy the second by emitting the EDN map in chat without calling Write or Edit. Claude was corralled
into tool use by `--allowedTools` at the CLI layer; Codex (which the codex-args function ships with no tool restriction)
had no such corral and inconsistently chose the chat path. When that happened, the parse-code-response fallback
extracted files from chat into an in-memory artifact, gates passed on the data, but the curator's environment check
found nothing on disk and correctly failed with `:curator/no-files-written`.

The new prompt:

- "Delivery — Tools Are the Mechanism" replaces "Output Format". The first sentence: the work product is files on disk.
- Explicit `Do NOT` list: emitting `:code/files` in chat, printing code blocks "for clarity", planning extensively in
  chat.
- The structured payload `submit` accepts no longer includes `:code/files` — the runtime derives that from Write/Edit
  calls. This removes the path that lets a model confuse "describe the work" with "deliver the work".
- Already-implemented response now goes through `submit` rather than a bare chat output.
- `:prompt/version` bumped to `1.1.0`.

The existing `parse-code-response` fallback tests still pass — they cover the "if the model emits chat anyway, parser
handles it" safety net, which remains valuable.

### 5. `:format` gate fail-closed (`components/gate`)

`check-format` and `repair-format` returned `(response/success {...})`, which produces `{:status :success ...}` with no
`:passed?` key. The gate machinery checks `(boolean (:passed? result))` — so every format-gate run was treated as
failing.

Other gates (syntax, lint, test, behavioral, no-secrets) correctly return `{:passed? bool}`. There were no tests on the
format gate; that's how this drifted in unnoticed.

Fix: return `{:passed? true ...}` from `check-format` and `{:success? true ...}` from `repair-format`. Drop the
now-unused `response.interface` require. Add `format_test.clj` covering both the value AND its acceptance by
`gate/passed?` so future regressions trip the test in either direction.

### 6. `bb miniforge` classpath: anomaly component

The `bb miniforge` task's `:extra-paths` was missing `components/anomaly/{src,resources}`. The schema component requires
`ai.miniforge.anomaly.interface`, so every `bb miniforge` invocation died at namespace load with `FileNotFoundException`
before reaching CLI dispatch.

Adding the two paths unblocks the task. The broader `bb.edn` classpath drift (~76 components on disk are absent from
this `:extra-paths` list) is a separate issue and should be addressed by deriving the classpath from `workspace.edn`
rather than maintaining the path list by hand. Tracked separately.

### 7. `bb harvest` CLI command

Pulls persisted task bundles back into the host repo as named branches. Closes the visibility gap between
`persist-workspace!` (which writes archives) and the user's host worktree (which had no automatic way to materialize
them).

```text
bb harvest                  # list all checkpoint workflows
bb harvest <workflow-id>    # fetch one workflow's bundles
bb harvest --all            # fetch every checkpoint on disk
```

Each bundle becomes a `harvest/<workflow-id>/<task-id>` ref in the host repo. Standard git tools take it from there:

```text
git log    harvest/<wf>/<task>      # see what the agent produced
git checkout harvest/<wf>/<task>    # try the alternative
git cherry-pick harvest/<wf>/<task> # integrate a chosen version
```

Override the checkpoint root via `MINIFORGE_CHECKPOINT_DIR` if needed.

## Why CLI harvest, not automatic merge

The orchestrator currently runs every DAG task in a scratch worktree off the same parent base, so tasks that should
depend on each other (per their declared `:dependencies`) actually run from a clean slate. In the dogfood verification
run that drove this PR, four of six tasks independently rewrote `components/classification/engine.clj` because each
agent was looking at the same un-extended base.

Auto-merging those task branches into the spec branch would be a conflict storm. A user-driven `bb harvest` is the safe
shape until **per-task base chaining** lands — the followup that has each task acquire a scratch worktree from its
dependency-task's branch rather than the spec branch. That work belongs in its own PR and architectural conversation.

## Verification

End-to-end dogfood on `work/generalized-classification-runtime.spec.edn`:

```text
✓ Phase :plan success (2.3m)
:completed 6, :failed 0, :iterations 5
✓ Workflow completed (4.5m planner + ~42m total)
```

Six bundles in `~/.miniforge/checkpoints/<workflow-id>/`, each with a fresh task commit carrying real
`components/classification/...` files (~430–760 lines per task). `bb harvest <wf>` round-trips them back as
`harvest/<wf>/task-<id>` refs. `git ls-tree refs/heads/harvest/<wf>/task-<id>` shows the agent's writes intact.

Pre-commit (lint + format + changed-brick tests) passes on every commit.

## Followups (not in this PR)

1. **Per-task base chaining.** Orchestrator should pass dependency-task's branch as the env-config `:branch` for
  downstream task acquisition, so tasks see each other's intermediate output. Without this, harvest is the user's
  recovery path; with it, the spec branch can naturally absorb chained work.
2. **Codex tool parity.** `codex-args` ships zero tool config (`--full-auto` only sandboxes commands, doesn't restrict
  tools). Claude's `--allowedTools` / `--mcp-config` lock the surface; Codex needs equivalent `-c` overrides into
  `~/.codex/config.toml` keys (`tools.<name>.enabled`, `[approval]`). Pending review of the Codex config schema.
3. **Podman as the default isolation tier.** Worktree should become the documented fallback; isolated containers should
  be the primary execution surface.
4. **`bb miniforge` classpath drift.** Derive `:extra-paths` from `workspace.edn` instead of the hand-curated list.
