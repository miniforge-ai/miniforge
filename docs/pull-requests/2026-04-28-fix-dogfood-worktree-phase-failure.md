# fix/dogfood-isolation-and-events

## Summary

Fixes the first set of regressions surfaced by dogfooding the behavioral
verification workflow spec.

The main failures were:

- `bb miniforge run --worktree ...` did not make that path authoritative for
  workflow execution
- a failed inner agent result in `:plan` could still look successful enough for
  the workflow to continue
- event persistence was noisy and fragile at write time
- the `bb miniforge` dev classpath was missing the new lifecycle EDN resources
  added during the FSM cleanup

This PR fixes those runtime seams and adds regression coverage around them.

## Key Changes

- preserved CLI `--worktree` into runtime execution opts in
  [run.clj](/private/tmp/mf-dogfood-fixes/bases/cli/src/ai/miniforge/cli/main/commands/run.clj)
- made workflow context prefer explicit execution worktree authority in
  [context.clj](/private/tmp/mf-dogfood-fixes/bases/cli/src/ai/miniforge/cli/workflow_runner/context.clj)
- replaced the hot-path CLI git metadata lookups with `clojure.java.shell`
  in [worktree.clj](/private/tmp/mf-dogfood-fixes/bases/cli/src/ai/miniforge/cli/worktree.clj)
- normalized phase status extraction so inner `:result {:status :error|:failed|:failure}`
  overrides outer `:status :completed` in
  [registry.clj](/private/tmp/mf-dogfood-fixes/components/phase/src/ai/miniforge/phase/registry.clj)
- hardened event file writes with parent-dir creation at write time in
  [sinks.clj](/private/tmp/mf-dogfood-fixes/components/event-stream/src/ai/miniforge/event_stream/sinks.clj)
- added missing lifecycle resource paths for the dev CLI in
  [bb.edn](/private/tmp/mf-dogfood-fixes/bb.edn) and
  [deps.edn](/private/tmp/mf-dogfood-fixes/deps.edn)

## Tests

- added CLI worktree propagation coverage in
  [run_test.clj](/private/tmp/mf-dogfood-fixes/bases/cli/test/ai/miniforge/cli/main/commands/run_test.clj)
- added workflow-context worktree authority coverage in
  [context_test.clj](/private/tmp/mf-dogfood-fixes/bases/cli/test/ai/miniforge/cli/workflow_runner/context_test.clj)
- added phase predicate regression coverage in
  [interface_test.clj](/private/tmp/mf-dogfood-fixes/components/phase/test/ai/miniforge/phase/interface_test.clj)
- added event sink parent-dir recreation coverage in
  [sinks_test.clj](/private/tmp/mf-dogfood-fixes/components/event-stream/test/ai/miniforge/event_stream/sinks_test.clj)

## Validation

- `clj-kondo --lint` on touched files
- `bb pre-commit`
- dogfood rerun:

  ```bash
  env XDG_CONFIG_HOME=/tmp/mf-dogfood-xdg \
    CLJ_CONFIG=/tmp/mf-dogfood-xdg/clojure \
    MINIFORGE_HOME=/tmp/mf-dogfood-home \
    bb miniforge run work/behavioral-verification-monitor.spec.edn \
    --worktree /tmp/mf-dogfood-behavioral-monitor-run2
  ```

## Dogfood Outcome

The rerun no longer advanced from failed `:plan` into `:implement`.
The workflow failed in the correct FSM state, durable event files were written
under `/tmp/mf-dogfood-home/events`, and the repo-root `.codex/config.toml`
permission failure did not recur.

The next remaining dogfood blocker is the planner/artifact-session path:
the run now fails cleanly at `:plan` with an inner timeout / missing-artifact
condition instead of drifting forward with split state authority.
