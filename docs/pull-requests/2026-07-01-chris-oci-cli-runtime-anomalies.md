# refactor: return OCI CLI runtime failures as data

## Summary

Convert the OCI CLI runtime executor's remaining cleanup-needed exception
paths to result-shaped data.

## Changes

- `with-acquisition-timeout` now returns `:acquire-failed` result data when
  the guarded body raises instead of rethrowing from the future boundary.
- Interrupted `run-runtime` and `run-runtime-process` calls now destroy the
  child process, re-interrupt the worker thread, and return an interrupted
  process result.
- Workspace bootstrap command failures now short-circuit as
  `:container-command-failed` result data instead of throwing through the
  container exec helper.

## Validation

- `clj-kondo --lint components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli.clj components/dag-executor/test/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli_test.clj`
- `clojure -M:dev:test -e '(require (quote [clojure.test :as t]) (quote
  ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)) (let [r (t/run-tests (quote
  ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)) failures (+ (:fail r) (:error r))] (shutdown-agents)
  (when (pos? failures) (System/exit 1)))'`
- Exceptions-as-data scanner: `oci_cli.clj` now has `0` cleanup-needed rows;
  repository total is `86`.
