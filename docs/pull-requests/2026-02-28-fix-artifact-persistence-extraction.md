# fix: Artifact persistence extraction from correct nested path

**Branch:** `fix/artifact-persistence-extraction`
**Date:** 2026-02-28
**Dependencies:** None

## Overview

Fixes `record-phase-artifacts` and `track-phase-files` in the workflow
execution layer to extract artifacts from the correct nested path in
phase results. Adds empty-completion validation to the runner. Moves
slow tests (>100ms) from brick tests to project tests.

## Motivation

During dogfooding, workflows completed successfully but
`:execution/artifacts` stayed empty and `:execution/files-written`
was always `[]`. The root cause: `record-phase-artifacts` read
`(:artifacts phase-result)`, but phase results are structured as
`{:name :implement, :result {:status :ok, :output {...}}, :status :completed}`.
There is no top-level `:artifacts` key — the code artifact lives at
`[:result :output]`.

Phase-to-phase handoff via `:execution/phase-results` already worked
correctly (verify and release both read from the right path), so the
bug was confined to the artifact/file tracking functions.

## Changes in Detail

### `execution.clj`

- **`record-phase-artifacts`**: Extract artifact from `[:result :output]`
  when output is a map.
- **`track-phase-files`**: Extract file paths from
  `[:result :output :code/files]` via `(mapv :path ...)`.

### `runner.clj`

- Added empty-completion validation after the pipeline loop: if a
  workflow reaches `:completed` with no artifacts and no phase results,
  downgrades to `:completed-with-warnings` and logs an
  `:empty-completion` error.

### Test performance fixes

Pre-commit was taking 10+ minutes because several brick tests
executed real phase pipelines, spawned subprocesses, or used
`Thread/sleep`.

### Hanging tests fixed

| Test | Issue | Fix |
|------|-------|-----|
| `phase.verify-test` | `run-tests!` spawns `bb test` recursively | Mocked `run-tests!` and `write-test-files!` |
| `phase.handoff-test` | Same `run-tests!` recursion via verify phase | Mocked in both verify-calling tests |

### Slow tests moved to project tests

| Test | Time | Reason |
|------|------|--------|
| `runner-test` (2 tests) | hung | `run-pipeline` with real phase pipelines |
| `dag-orchestrator-test` | 63s | 24 tests with real DAG execution |
| `dag-executor.executor-test` | 57s | 20 tests with real DAG execution |
| `orchestrator.interface-test` | 3s | orchestration setup |
| `artifact-persistence-test` | 3s | filesystem fixtures |
| `self-healing.backend-health-test` | 689ms | Thread/sleep calls |
| `tui-views.subscription-test` | 573ms | Thread/sleep calls |
| `tui-engine.core-test` | 324ms | Thread/sleep + deref timeout |
| `web-dashboard.interface-test` | 236ms | slow setup |
| `tui-views.pr-views-test` | 207ms | slow setup |
| `knowledge.interface-test` | 180ms | slow setup |
| `llm.progress-monitor-test` | 124ms | above threshold |
| `gate.pipeline-test` | 122ms | above threshold |
| `tui-views.view-test` | 121ms | above threshold |
| `evidence-bundle.interface-test` | 119ms | above threshold |
| `tui-views.persistence-test` | 105ms | above threshold |
| `tool-registry.integration-test` | hung | spawns clojure-lsp subprocess |
| `phase.handoff-test` | slow | requires all phase implementations |

`runner_test.clj` stripped to only require `ai.miniforge.phase.release`
(which registers `:done`), runs in ~4s.

`bb.edn` `test:integration` updated to include all moved test
namespaces.

## Testing Plan

- `bb test` — brick tests, should complete in <30s for workflow brick
- `bb test:integration` — project tests, includes moved slow tests
