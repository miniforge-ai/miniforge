<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Split pipeline-runner run.clj into stage, spawn and scheduler namespaces

## Overview

Mechanical split of `pipeline_runner/run.clj` into six namespaces of at most
three strata each. No behavior change.

## Motivation

`run.clj` had no `Layer N` headings, so stratum-lint skipped it. Its call graph
infers nine strata against rule 210's budget of three. The first edit to the
file arms the pre-commit `stratum-lint --fix`, which then fails SL003, so no
change to the runner can land until the file is split.

The next changes need it. #2422 adds a preflight to `execute-pipeline`. #2435
asks for a stage executor callable on its own, apart from the scheduling loop.
This split puts the stage executors in their own namespaces first.

## Changes in Detail

| Namespace | Contents |
| --- | --- |
| `stage-result` | `stage-result`, `timestamps`, exception and anomaly context |
| `connector-stage` | ingest and publish executors, auth and cursor helpers, `default-config` |
| `processing-stage` | transform and validate executors |
| `spawn` | spawn requests to child stages, DAG wiring |
| `scheduler` | `run-stage` dispatch, input collection, one scheduling step |
| `run` | `run-pipeline-dag` loop and `execute-pipeline` |

1. Every function body moved unchanged. Cross-namespace callers use `:refer`,
   so call sites keep their original symbols.
2. Functions now called from another namespace changed from `defn-` to `defn`.
   `auth-keys` and `max-exception-causes` stay private.
3. New files carry the Apache header; stratum-lint added `^{:stratum n}`
   metadata and `Layer N` headings.
4. `interface.clj` is unchanged; it still calls `run/execute-pipeline`.
5. `interface_test.clj` gets the stratum-lint autofix: `Layer N` headings,
   `^{:stratum n}` metadata, and defs regrouped by stratum. The file had no
   headings, so its first edit would pull this whole-file rewrite into an
   unrelated commit.

## Behavior-neutral proof

A Babashka script read every top-level form of the old `run.clj` and of the six
new files with `*read-eval*` false. It dropped `ns` and `comment` forms, treated
`defn-` and `defn` alike, normalized anonymous-fn gensyms, and compared the
multisets of `pr-str`. Result: 31 code forms on each side, identical. Metadata
is not printed, so `^{:stratum n}` and `^:private` do not affect the comparison.
The same check on `interface_test.clj` before and after its autofix: 75 forms
each, identical.

## Testing Plan

1. `pipeline-runner` interface tests and ETL `runner`, `main` and `workbench`
   tests: 46 tests, 154 assertions, 0 failures.
2. clj-kondo: 0 errors, 0 warnings on `components/pipeline-runner/src`.
3. `bb lint:stratum`: no findings.

## Budget override

This is one commit over the 200-line commit budget and the 600-line PR budget:
moving a 434-line file counts its removal and its new copies. An intermediate
commit cannot be smaller, because any commit that touches `run.clj` before it
reaches three strata fails SL003.

## Deployment Plan

None. Internal namespaces only; the component interface is unchanged.

## Related Issues/PRs

Prepares #2422 and #2435, tracked under #2421.

## Checklist

- [x] Form-multiset proof: identical.
- [x] Runner and ETL tests pass.
- [x] Lint and stratum lint clean.
