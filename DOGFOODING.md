<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Miniforge Dogfooding Guide

This guide is for running Miniforge SDLC on the Miniforge repository itself.
The current loop is work-spec based: choose the next prioritized
`work/*.spec.edn`, run it through `bb miniforge run`, and resume from the
workflow checkpoint whenever a run is interrupted.

## Pick the Next Spec

Render the queue before every dogfood session:

```bash
bb work:queue
```

Use `work/QUEUE.md` as the source of truth. Start with the highest-priority
ready spec in the active dogfood theme unless the current session has a more
specific target. In practice this usually means the first `blocker` or `high`
row under `dogfood-resilience` whose dependency marker is ready.

Prefer passing the spec path explicitly:

```bash
bb dogfood work/worktree-persistence-scratch-branch.spec.edn
```

If no spec path is provided, `bb dogfood` uses the current default in
`tasks/dogfood.clj`.

## Prerequisites

`bb dogfood:check <spec>` verifies the pieces that matter for a live run:

```bash
bb dogfood:check work/worktree-persistence-scratch-branch.spec.edn
```

Required:

- OpenCode CLI. Provider API keys for dogfood agent runs are configured
  through OpenCode, not read directly by Miniforge.
- GitHub auth. Either set `GITHUB_TOKEN` or authenticate `gh`.
- A clean tracked working tree. Untracked local files are allowed, but staged
  or unstaged tracked changes must be handled before a dogfood run.
- The target spec file exists.

Useful optional environment:

```bash
export MINIFORGE_CHECKPOINT_DIR="$HOME/.miniforge/checkpoints"
```

The checkpoint directory defaults to `~/.miniforge/checkpoints`.

## Start a Run

Dry-run the exact command first:

```bash
bb dogfood:dry-run work/worktree-persistence-scratch-branch.spec.edn
```

Start the run:

```bash
bb dogfood work/worktree-persistence-scratch-branch.spec.edn
```

`bb dogfood` is a thin development wrapper around:

```bash
bb miniforge run <spec-path>
```

When `gh` is authenticated but `GITHUB_TOKEN` is not exported, the wrapper
passes `GITHUB_TOKEN=$(gh auth token)` into the Miniforge process.

## Resume, Do Not Restart

When a dogfood run stops, do not re-run the same spec from scratch if a
checkpoint exists. Resume the workflow:

```bash
bb miniforge resume <workflow-id>
```

`bb miniforge run <spec> --resume <workflow-id>` still exists for
compatibility, but `bb miniforge resume <workflow-id>` is the preferred
surface.

Use status to inspect a run:

```bash
bb miniforge status <workflow-id>
```

Use checkpoint discovery to find recoverable workflows and task bundles:

```bash
bb harvest
```

Checkpoint data is stored under:

```text
~/.miniforge/checkpoints/<workflow-id>/
```

or under `MINIFORGE_CHECKPOINT_DIR` when that environment variable is set.

## Recover Task Work

Some runs persist task bundles for completed DAG tasks. To fetch those bundles
back into the host repository as inspectable branches:

```bash
bb harvest <workflow-id>
```

Each bundle becomes a branch-like ref:

```text
harvest/<workflow-id>/<task-id>
```

Inspect with normal Git tools:

```bash
git log harvest/<workflow-id>/<task-id>
git diff main...harvest/<workflow-id>/<task-id>
```

Cherry-pick or merge only after reviewing the recovered work. Harvesting is a
manual recovery tool; it does not update the current branch automatically.

## Monitoring

Use the CLI status command for a specific workflow:

```bash
bb miniforge status <workflow-id>
```

Use the local dashboard when you want a live view:

```bash
bb miniforge web --port 7878
```

GitHub PRs created by a dogfood run still use the normal GitHub workflow:

```bash
gh pr list
gh pr view <number> --web
gh pr checks <number> --watch
```

## Stop Policy

If you need to stop a run:

1. Prefer letting the current phase finish so Miniforge writes the newest
   checkpoint.
2. If the process is stuck, interrupt it.
3. Find the workflow id in output, events, status, or `bb harvest`.
4. Resume with `bb miniforge resume <workflow-id>`.
5. Only start `bb dogfood <spec>` again when there is no useful checkpoint.

## After a Run

Dogfooding is successful when the run either completes the spec or exposes a
reproducible product bug. After each run:

1. Review any PR it opened and get it through merge.
2. Record or fix the next blocking bug surfaced by the run.
3. Re-render `bb work:queue`.
4. Resume the same workflow if it checkpointed, otherwise start the next
   highest-priority ready spec.
