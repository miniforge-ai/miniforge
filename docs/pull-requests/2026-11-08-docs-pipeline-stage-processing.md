<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Pipeline stage requirements and declared pass-through

## Overview

A user guide for Data Foundry pipeline stages: what each stage family must
name, how a stage declares that it skips processing on purpose, what preflight
reports, how spawned stages are checked, and what happens when a quality rule
fails to run. This is the documentation criterion of #2422 ("document how
optional stages differ from required stages").

## Motivation

The #2422 code PRs (#2444, #2446, #2454, #2448, #2452) changed what a pipeline
must declare. A stage that named no processing used to pass records through;
it is now refused unless it declares `:stage/pass-through? true`. The rules
were described only in namespace docstrings and PR docs.

## Changes in Detail

1. New `docs/user-guide/pipeline-stages.md`:
   1. Required processing per family, from `preflight/stage-checks`.
   2. Declared pass-through, its receipt, the conflict rule, and that connector
      stages ignore the flag.
   3. Every preflight problem kind, and `mf etl validate`.
   4. Executor checks on spawned stages, and that a failed stage halts the run.
   5. Rule errors as execution errors.
2. `docs/INDEX.md` links the guide under "Using Miniforge".

## Testing Plan

Documentation only. Each statement was checked against `preflight.clj`,
`preflight/stage_checks.clj`, `processing_stage.clj`, `scheduler.clj`,
`connector_stage.clj`, `data_quality/invoke.clj`, the ETL runner and CLI
(`bases/etl`), and the `risk-data` pack.

## Deployment Plan

None.

## Related Issues/PRs

Part of #2422, under tracker #2421. The #2422 gate report waits for the shared
qualification harness.

## Checklist

- [x] Optional (pass-through) and required stages documented.
- [x] Linked from the docs index.
