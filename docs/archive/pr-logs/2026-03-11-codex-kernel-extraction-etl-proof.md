<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Kernel Extraction and ETL Proof

## Overview

Extract the workflow kernel away from software-factory-specific assumptions,
validate the seams with a minimal financial ETL workflow, and fix the
integration-test regressions exposed by the new boundaries.

## Motivation

The governed workflow platform needs a cleaner open-core shape:

- DAG and workflow execution should not assume PR merge semantics.
- workflow triggering and publication should support non-SDLC use cases.
- policy-pack SDK surfaces should stay generic while PR-specific evaluation
  moves into the software-factory app layer.
- a second concrete workflow family should run through the kernel without
  software-factory conditionals.

This change proves that with a minimal ETL workflow while keeping the current
software-factory behavior working.

## Changes in Detail

- Add DAG execution state profiles so kernel runs can complete without PR merge
  semantics, while software-factory remains the default profile.
- Add generic event-trigger creation and keep PR merge triggering as an alias.
- Add a generic local directory publisher and configurable phase-handler
  injection for non-SDLC workflow families.
- Move PR-specific policy-pack evaluation behind a software-factory namespace.
- Preserve domain-native DAG task ids instead of coercing non-UUID ids into
  synthetic UUIDs.
- Add a minimal `financial-etl` workflow and end-to-end proof test covering:
  acquire, extract, canonicalize, evaluate, and publish-report.
- Harden TUI enrichment handling for map and simple scalar/keyword fixtures.
- Fix integration-test environment issues:
  - disable git signing inside release-executor integration repos
  - avoid Datalevin-backed store usage where sandbox permissions break it
  - skip dashboard socket-bind assertions when the environment blocks bind
  - make MCP artifact-session dev fallback independent of current working dir
  - make MCP artifact-server test shutdown tolerant of already-closed streams
  - make Docker executor tests skip when container startup is not actually available

## Testing Plan

- `bb test`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`

Notes:

- `bb test:integration` now passes, but babashka/process still emits
  background `sysctl failed` warnings in this sandboxed environment.
- Docker and Kubernetes integration cases are skipped when those runtimes are
  unavailable.

## Deployment Plan

- No special deployment steps.
- Merge as a single feature branch because the kernel seams and test fixes are
  coupled and need to land together.

## Related Context

- open-core boundary review and repo split research in `docs/research/`
- flagship-first extraction plan validated against the ETL proof case

## Checklist

- [x] DAG runtime supports domain-neutral completion semantics
- [x] Workflow runtime supports generic event triggering
- [x] Publication has a kernel-level directory implementation
- [x] PR-specific policy evaluation is no longer the kernel-facing default
- [x] Minimal ETL workflow runs end-to-end through the extracted seams
- [x] `bb test` passes
- [x] `bb test:integration` passes
- [x] `bb build:cli` passes
- [x] `bb build:tui` passes
