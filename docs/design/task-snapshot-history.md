<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Task snapshot history outside the agent's reach

Status: draft, 2026-11-08. Scope: local-mode task worktrees in `dag-executor`. Once accepted, the event and evidence
contracts go into N3 and N6, and the resume rule goes into N2-delta §8. This doc is design only, with no code.

Reference: PAODO-Workspaces `doc/adr/accepted/platform/adr-workspace-git-versioning.md` and `lib/infra/git/`.

## Problem

In local mode each task runs in a linked worktree of the host checkout (`create-worktree` in
`protocols/impl/worktree.clj`). The worktree owns HEAD, the index and the files. The config and all other refs are
shared with the host. The implementer role has `Bash` and runs git steps (`config/agent/tool-policy.edn`). Every
record the platform keeps of a task's files can be changed by the agent, or is built from state the agent controls:

1. **Task branch.** It lives in the shared ref store, so the agent can reset, amend or delete it.
2. **Persist bundle.** `archive-bundle!` runs `git add -A` and `git commit --no-verify` inside the worktree under
   the shared config. So it follows `.gitignore` and `.gitattributes` the agent edited, and runs any
   `core.fsmonitor` or post-commit hook that config names. Every phase overwrites the one file,
   `<archive-dir>/<workflow-id>/<env-id>.bundle` (`persist-opts` passes the environment id as `:task-id`).
3. **Scratch refs.** `scratch-commit!` writes `refs/miniforge/scratch/<workflow-id>` into the shared ref store. Only
   the interface exports it; no production code calls it.

Two problems found while checking this against the code:

1. **A `--from-phase` rewind restores later work.** `apply-from-phase` (`bases/cli/.../commands/resume.clj`) picks
   the checkpoint of the last kept phase. `restore-local-workspace-checkpoint!` passes only `:bundle-path` and
   `:branch` to `restore-from-bundle!`, which fetches the bundle's branch tip. That bundle was overwritten by the
   latest phase, so the rewind starts from work its docstring says it never restores. The event's
   `:workspace/commit-sha` is recorded, but the restore does not use it.
2. **`host-git-guard` does not watch code-execution keys.** Besides remote-tracking refs, the only config keys it
   compares are remote URLs and `insteadOf` (`redirect-config-pattern`). Running `git config core.hooksPath <dir>`
   in a linked worktree writes the host's `.git/config` (tested). The host's next commit, including the operator's
   own, then runs those hooks.

## Goals

1. Keep a per-task history of the full work tree, taken by the platform at fixed points, in a store that git
   commands run in the work tree cannot reach.
2. Restore any recorded point by a verified SHA.
3. Record each SHA in the event stream when the snapshot is taken, for resume and evidence.

Out of scope: stopping host config and ref changes (decision 1), side effects outside the tree, and governed capsules
(decision 3).

## What "cannot reach" means here

In local mode the agent runs on the host as the operator's user. File tools are confined to the worktree
(`path-refusal` in `agent/tool_policy.clj`). `Bash` is not sandboxed: `:bash/deny-patterns` is a list of regexes.
So no host path is fully out of reach. The store is out of reach of:

1. git commands run in the work tree;
2. the file tools;
3. the work tree's own ignore, attribute and hook settings.

A deliberate `Bash` write into the store is detected, not prevented. The SHA in the event stream fixes the tree it
names, so a changed store no longer matches. Changing the history unnoticed means editing two platform stores on
purpose, which ordinary git use cannot do. That ordinary git use is what happened on 2026-08-06. PR 2 also adds the
snapshot root to `:bash/deny-patterns`. In governed mode the capsule sees its own workdir (a tmpfs unless the plan
mounts one) and only the host paths its plan mounts, so a store on the host is out of reach by construction.

## Design

### Store

1. One bare git dir per task: `<snapshot-root>/<workflow-id>/<task-id>.git`. The new user-config key
   `:workflow/snapshot-root` defaults in code to `<miniforge-home>/snapshots`, as `:workflow/worktree-root` does
   (`default-base-path`). Like that key, it stays out of `default-user-config.edn`, so `MINIFORGE_HOME` moves it.
   `task-id` is the full id given to `acquire-environment!`; the environment id holds only its first 8 characters.
2. `git --git-dir <dir> init`, with no `--work-tree`, so no `core.worktree` is written. Before the first add, write
   `info/attributes` containing `* -text -filter -ident !eol !working-tree-encoding`. `info/attributes` outranks
   in-tree `.gitattributes`. Without it, a work tree with `text=auto eol=crlf` was stored with LF line endings
   instead of the CRLF on disk (tested).
3. Every command is `git --git-dir <dir> --work-tree <worktree>` with
   `-c core.excludesFile=/dev/null -c core.hooksPath=/dev/null -c core.fsmonitor=false -c commit.gpgsign=false` and
   a fixed `user.name`/`user.email`. The process starts from an empty environment holding only `PATH`, `LC_ALL=C`,
   `GIT_CONFIG_GLOBAL=/dev/null` and `GIT_CONFIG_NOSYSTEM=1`. An explicit `--git-dir` overrides `GIT_DIR` but not
   an inherited `GIT_INDEX_FILE` or `GIT_OBJECT_DIRECTORY`; a stray `GIT_INDEX_FILE` moved the index (tested). With
   an explicit `--git-dir` git does no discovery, so it never opens the work tree's `.git` file or the host's
   `.git/config`.
4. A snapshot is `add --all --force` and then a commit only if the tree changed. The first commit uses
   `--allow-empty`, so every store has a root commit. Tested: ignored files are kept, the
   worktree's own `.git` file is skipped, and a nested repo is stored as a gitlink. An uninitialized submodule
   directory is empty, so it is not recorded. So the baseline is read from disk and never assumed equal to
   `base-sha`'s tree. On this repo the two differ by the empty `standards/miniforge` and an ignored
   `.claude/settings.local.json`.
5. No alternates. Borrowing the host's objects made the first snapshot 13x faster. But then `git gc` run in the
   agent's worktree could delete objects the snapshots depend on.

### When snapshots are taken

| Point | Hook | Tag |
|-------|------|-----|
| baseline | `acquire-environment!`, after the worktree exists | `baseline/<n>` |
| phase | `persist-workspace!`, before the persist commit | `phase/<n>-<phase>` |
| final | `release-environment!`, before the worktree is removed | `final/<n>` |

`n` is one counter over all the store's tags, counted from the tags already there, so the order survives a restart
and a resume that reuses the store. A snapshot with no change makes no commit,
but it still tags the current commit and is still reported. So "state after phase X" always resolves.
Release runs in the runner's cleanup (`runner-lifetime/protect!`), so a run that throws still gets its final
snapshot. A killed process does not; its last phase snapshot stands.

### Executor wiring

A wrapper record `SnapshottedExecutor` (new, `protocols/impl/snapshotted.clj`), shaped like `HostGuardedExecutor`.
It reports its delegate's `executor-type`, because selection code branches on `:worktree`. It is composed outermost
in `create-executor-registry` (`executor.clj`):
`(snapshotted (guard-host-git (create-worktree-executor ...)))`. Outermost, because on drift the guard returns its
error in place of the release result, which would drop snapshot data reported from inside it.

1. `acquire-environment!`: delegate, then take the baseline. The workflow id comes from env-config `:workflow-id`,
   which no caller passes today. Both callers that acquire a worktree add it: `acquire-execution-environment!` and
   `task-executor/runner.clj`. The worktree executor's scratch registry also reads that key, but only to record an
   in-memory entry. With no id there is no store, and the snapshot reports `:no-workflow-id`.
2. `persist-workspace!`: take the phase snapshot, then delegate. `persist-opts` gains `:phase`.
3. `release-environment!`: take the final snapshot, delegate, then `git repack -adq`.
4. `restore-workspace!` with `:snapshot-dir`, `:snapshot-sha` and `:tree-sha`: verify `rev-parse --verify
   <sha>^{commit}` and that the commit's tree equals `:tree-sha`. Then `add --all --force` and `reset --hard <sha>`.
   The add puts the target's current files in the index, so the reset also deletes files the snapshot lacks. Tested
   both in place and into a fresh worktree: the trees matched exactly and the `.git` file was left alone. Other
   opts go to the delegate unchanged.

Every result carries `:workspace/snapshot`: `{:kind :phase :sha :tree :tag :dir :changed? :bytes}`, or
`{:kind :error}` on failure. Writes to one store are serialized by a JVM lock keyed on its git dir. A second
process fails on git's `index.lock`; it does not wait.

**A snapshot failure never fails the action it wraps, but it is never silent.** It reports
`:workspace/snapshot-failed`, and the gap shows in evidence (N11 §7.3).

Governed mode also acquires a host worktree (`governed-environment/acquire!`), but the agent does not work in it.
PR 2 changes the registry config there from `{:worktree {}}` to `{:worktree {:snapshots? false}}`.

## Decisions

### 1. Beside linked worktrees, not a switch to clones

The store and clones fix different things. The store protects the platform's record of the files. Clones stop the
shared config and refs. An agent in a clone can still rewrite its own history, so the store is needed either way.
It also works unchanged on a clone's work tree. Clones are the real fix for the problem `host-git-guard` reports,
but they change the task flow:

1. `branch-registry` cuts child worktrees from parent task branches in the shared ref store.
2. Release pushes from the worktree.
3. Resume fetches a bundle into host refs before `worktree add`.

Each of these would need an explicit fetch or push between the clone and the host. `--local` copies objects when
the worktree root and the checkout sit on different volumes. That is the default here: `~/.miniforge/worktrees`
versus `/Volumes/Work`, with 105 MB of objects. Clones need their own design (follow-up 4).

### 2. Feeding events, resume and evidence

1. **Events (N3).** A new `:workspace/snapshotted` event with `env-id`, `phase`, `snapshot-kind`
   (`:baseline`/`:phase`/`:final`), `snapshot-sha`, `tree-sha`, `snapshot-tag`, `snapshot-dir`, `changed?` and
   `bytes`, plus `:workspace/snapshot-failed`. Both are added beside `:workspace/persisted` in
   `event_stream/schema.clj`, with constructors in `event_stream/core.clj` and `interface/events.clj`. They are
   published from the wrapped results:
   1. acquire, in `runner_environment.clj`;
   2. every phase boundary, whatever the persist did, in `runner_environment.clj` and
      `environment_notifications.clj`;
   3. release, in `boundary/environment.clj` and `runner_cleanup.clj`.
2. **Resume (N2-delta §8).** `extract-workspace-snapshots` goes in `workflow_resume/core.clj`, beside
   `extract-workspace-checkpoints`. `apply-from-phase` picks the snapshot of the last kept phase, by the same rule
   as the checkpoint. The worktree is first placed at that phase's `:workspace/commit-sha`, which is follow-up 1's
   fix. After acquire, `restore-workspace!` resets its files to the snapshot. This is a second step: the bundle
   restore has to happen before the worktree exists, and the snapshot restore needs it to exist. With no usable
   snapshot (pruned, or a bad SHA), resume falls back to the bundle and warns. A new attempt carries the old run's
   snapshot events forward, as `resume.clj` already does for persisted checkpoints.
3. **Evidence (N6).** An optional `:evidence/workspace-snapshots` field holds `[{:kind :phase :sha :tree :tag}]`.
   It is assembled in `runner_outcome.clj` next to `:evidence/execution-mode`, declared in
   `evidence_bundle/schema.clj`, and projected in `projection.clj`. Evidence carries the SHAs only, never the
   content. The store holds ignored files that the persist secret scan never sees (the measurement above captured
   `.claude/settings.local.json`), so the store is never pushed or exported. A verifier checks each SHA against the
   store and runs `git fsck`.

### 3. Governed capsules: not now

1. No shared common dir. The capsule clones into its own `/workspace`, a tmpfs unless the plan mounts one
   (`bootstrap-workspace!`, `build-runtime-fs-args`). The registry applies `host-git-guard` only to the worktree
   executor.
2. Anchors already exist by SHA. Bootstrap records `:base-sha`. `git-persist!` pushes a commit whose SHA is
   recorded, and the capsule holds no credential for that remote, since the credential joins platform-issued
   network commands only (`workspace-exec-fn`).
3. What a capsule lacks compared with local mode: ignored files, and the state of a capsule that dies before
   persist. Capturing either means streaming the tree out through `exec`. `copy-from-container` reads the whole
   stream into memory (`:out-bytes`), which is unsafe until the output-ceiling work from the PAODO review lands. If
   that is wanted later, the same store and events apply, fed by `tar -c` streamed into a host staging dir.

### 4. Pruning and disk growth

Measured on this checkout (5,589 files, 47 MB): the first snapshot took 5.4 s and wrote 37 MB of loose objects. A
repeat with no change took 0.15 s, and `repack -ad` packed the store to 15 MB in 3.5 s. Later snapshots add only
changed blobs.

1. Repack at release, so a finished task costs about one packed tree plus its changes.
2. Retention follows checkpoints (N2-delta §7): keep at least 30 days, never collect a pinned run, run at most once
   a day. Whole `<workflow-id>/` dirs are deleted by the `gc-checkpoints!` pass, under its existing safety rules: a
   root outside `MINIFORGE_HOME` is refused and live runs are kept. Tests set `MINIFORGE_HOME` or pass an explicit
   snapshot root, never the real home.
3. No size ceiling in the first version. Each event carries `bytes` (`count-objects -v`), so growth shows. Open
   risk: the store sits on the same volume as the worktrees, and an agent that writes gigabytes into the tree
   doubles them.

## Implementation plan

Each PR stays under the 600-line budget and merges bottom-up.

| PR | Stratum | Contents | Depends on |
|----|---------|----------|------------|
| 1 | domain | `dag_executor/snapshot_store.clj`: init, snapshot, verify, restore, repack against an explicit git dir; tests built on the cases above | — |
| 2 | application | `snapshotted.clj` wrapper; composition in `executor.clj`; `:snapshots? false` in `governed_environment.clj`; `:workflow/snapshot-root` resolution; env-config `:workflow-id`; deny-pattern | 1 |
| 3 | adapter | N3 amendment; event schema and constructors; publication at acquire, phase and release | 2 |
| 4 | application | N2-delta §8 amendment; resume and rewind restore from snapshots | 3, follow-up 1 |
| 5 | adapter | N6 amendment; `:evidence/workspace-snapshots`; verifier | 3 |
| 6 | infrastructure | snapshot dirs in the checkpoint GC pass | 2 |

## Follow-ups outside this design

1. Rewind restores the latest phase's bundle. Fix: restore at the event's `:workspace/commit-sha`. This is a live
   bug, so it lands first.
2. `host-git-guard` should also watch config keys that run commands or pull in other config, for example
   `core.hooksPath`, `core.fsmonitor`, `core.sshCommand`, `include.path`, `includeIf.*`, `credential.helper`,
   `filter.*` and `diff.*.textconv`.
3. `archive-bundle!`'s git calls should pass `core.hooksPath=/dev/null` and `core.fsmonitor=false`, as the store does.
4. A design for task clones in place of linked worktrees. This is the prevention that `host-git-guard`'s docstring
   names.
5. Retire `scratch-commit!` and `notify-file-written!` once PR 4 lands: the store does what they were meant to, out
   of the agent's reach.
