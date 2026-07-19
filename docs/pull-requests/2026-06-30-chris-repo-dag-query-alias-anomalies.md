<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Return Repo DAG Query Alias Anomalies

## Summary

The repo-dag read-only query aliases now preserve the exception-as-data
contract instead of translating missing DAGs back into thrown `ex-info`
values.

## Changes

- Removed the repo-dag anomaly-to-ex-info compatibility helper.
- Made `compute-topo-order`, `affected-repos`, `upstream-repos`,
  `merge-order`, and `validate-dag` delegate directly to their
  anomaly-returning implementations.
- Removed deprecated/throwing interface documentation from the stable query
  aliases.
- Updated query and edge-case tests to assert returned `:not-found` anomalies
  from the stable aliases.

## Verification

- `clojure -M:dev:test -e '(require ... repo-dag query namespaces ...) ...'`
- `clojure -M:dev:test -e '(require ... compliance-scanner ...) ...'`

Scanner result for `components/repo-dag/src/ai/miniforge/repo_dag/core.clj`:
`0` cleanup-needed rows.
