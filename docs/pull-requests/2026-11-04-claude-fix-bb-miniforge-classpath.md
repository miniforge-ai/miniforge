<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(dev): `bb miniforge run` loads every brick the CLI project uses

## Overview

`bb miniforge run`, the command `bb dogfood` launches, failed before its
first phase:

```text
Could not locate ai/miniforge/opsv_actuation/interface.bb, ai/miniforge/opsv_actuation/interface.clj or ai/miniforge/opsv_actuation/interface.cljc on classpath.
```

This PR adds the missing bricks and dependency to the `miniforge` task.
A new test fails when that list drifts from the CLI project again.

## Motivation

The `miniforge` bb task runs the CLI under Babashka on a hand-kept
`:extra-paths` and `:extra-deps` list. The phase loader requires every
namespace in `config/phase/namespaces.edn`, including
`ai.miniforge.phase-opsv.registry`. That namespace now reaches
`opsv-actuation` (through `phase-opsv.pr-dispatch`) and `clojure.data.json`
(through `phase-opsv.boundary.json-document`, #2340). Neither was on the
task's classpath. The built CLI takes its classpath from
`projects/miniforge/deps.edn`, which includes `opsv-actuation` directly and
`clojure.data.json` through `phase-opsv`. CI never launches
`bb miniforge run`, so nothing caught it;
the comment above `repo-analyzer` in the same list records the same failure
from an earlier gap.

## Changes in Detail

1. `bb.edn` `miniforge` task: add the src and resources dirs of the nine
   bricks `projects/miniforge/deps.edn` depends on that the list lacked.
   These are `governance-provenance`, `knowledge-pack`, `kubernetes-target`,
   `opsv-actuation`, `opsv-adapter-simulated`, `opsv-provider-github`,
   `pr-label-watcher`, `workbench-orchestration-adapter`, and `workflow-opsv`.
   Also add `org.clojure/data.json` 2.4.0, the version `phase-opsv` declares.
2. `development/test/bb_miniforge_classpath_test.clj` (new):
   1. `miniforge-task-lists-every-project-brick-test`: every existing src
      and resources dir of every brick in `projects/miniforge/deps.edn` is
      in the task's `:extra-paths`.
   2. `miniforge-task-loads-cli-and-phases-test`: runs `bb` with the task's
      deps and paths and requires `ai.miniforge.cli.main` and every
      namespace in the phase loader's list.

The `mcp-context-server` task, which agents use for context tools, already
loads its entry namespace on its own list; it is unchanged.

## Testing Plan

1. With this branch's `bb.edn`, both new tests and `deps_resources_test`
   pass (4 tests, 0 failures).
2. With main's `bb.edn`, both new tests fail; the load test reports the
   same `opsv_actuation/interface` error as the dogfood run.

## Deployment Plan

Development tooling only. The built CLI's classpath comes from
`projects/miniforge/deps.edn` and does not change.

## Related Issues/PRs

1. #2340 added the `clojure.data.json` require in `phase-opsv`.
2. #2197 runs `development/test` in CI, which now runs these tests.

## Checklist

- [x] New tests fail on main and pass here
- [x] `deps_resources_test` still passes
