# feat/chain-output-extraction — PR1a

## Layer

Domain (Workflow Engine)

## Depends on

- None (base of chain executor stack)

## Overview

Adds `:execution/output` extraction to `run-pipeline` in the workflow runner,
enabling downstream chain executors to consume synthesized outputs from
completed workflow phases.

## Motivation

The meta-loop chaining engine requires a structured output contract from each
pipeline run so that one workflow's results can feed into the next. Without an
explicit extraction step, downstream chain executors would need to reach into
raw phase-results and artifacts themselves, coupling them to internal pipeline
structure.

This PR introduces a single `extract-output` function that synthesizes
`:execution/output` from `:execution/artifacts` and `:execution/phase-results`,
giving chain executors a clean, stable interface to consume.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/workflow/src/ai/miniforge/workflow/runner.clj` | Added private `extract-output` fn; wired into `run-pipeline` post-execution |
| `components/workflow/src/ai/miniforge/workflow/context.clj` | Added `:execution/output nil` to initial context map |
| `components/workflow/test/ai/miniforge/workflow/runner_test.clj` | 3 new tests covering extraction from artifacts, phase-results, and empty runs |

**+69 LOC** across 3 files.

## Testing Plan

- [x] 3 unit tests for `extract-output` covering artifact extraction, phase-result extraction, and nil/empty cases
- [ ] Pre-commit hooks pass (lint, format, test, graalvm)
- [ ] Integration with PR1b (chain executor) once that lands

## Deployment Plan

No runtime impact — additive only. Merges to `main` and is consumed by PR1b
(feat/chain-executor).

## Related Issues / PRs

- **PR1b** (chain executor) depends on this PR
- Part of the meta-loop chaining engine work

## Checklist

- [x] Tests written and passing
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected
- [x] Babashka / GraalVM compatible
