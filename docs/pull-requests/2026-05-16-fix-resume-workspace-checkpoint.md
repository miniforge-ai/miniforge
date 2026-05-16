# Fix: Resume From Workspace Checkpoints

## Overview

This PR fixes checkpoint resume so failed dogfood runs continue from the latest
persisted workspace branch or bundle instead of reacquiring a fresh worktree
from the original spec base.

## Motivation

Dogfood run `3927baf8-c9db-44d3-b5fb-5a1552dbe554` had 59
`:workspace/persisted` events and durable task bundles, but resume only
threaded completed-DAG artifacts into the next run. When the checkpoint failed
inside a repair loop, there were no completed DAG artifacts, so the next
`implement` attempt started from a clean base and failed with
`:curator/no-files-written`.

## Changes in Detail

- Resume reconstruction now extracts persisted workspace checkpoints from
  `:workspace/persisted` events.
- CLI resume passes the latest workspace checkpoint into `run-pipeline`.
- Local environment acquisition restores the checkpoint bundle into the host
  repo before acquiring a worktree and prefers the persisted task branch as the
  acquisition branch.
- Workflow messages now include a localized workspace restore failure message.

## Dogfood Notes

- The failing resume had a latest persisted branch of `task-79ca5c82` at
  commit `2a8f7e6ac8963e71e60e1c6c4f18f3ff3923f5c5`.
- The failed retry acquired a new clean worktree `task-cbe8110c` and then
  failed with `:curator/no-files-written`.
- With this change, resume has enough provenance to continue from the last
  promoted workspace state.

## Testing Plan

- `clojure -M:dev:test -e ... workflow-resume.core-test`
- `clojure -M:dev:test -e ... cli.main.commands.resume-test`
- `clojure -M:dev:test -e ... workflow.runner-test`
- `bb pre-commit`

## Deployment Plan

Merge after review. This is scoped to checkpoint resume and local worktree
acquisition.

## Related Issues/PRs

- Dogfood target: `work/event-log-tool-visibility.spec.edn`
- Follow-up from the artifact provenance work in the May 15 dogfood PR.

## Checklist

- [x] Resume records workspace provenance from persisted events.
- [x] CLI resume threads workspace checkpoint data into the next run.
- [x] Local worktree acquisition can start from the persisted task branch.
- [x] Focused tests cover reconstruction, CLI plumbing, and runner plumbing.
