<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Align LLM HTTP failures with Exceptions as Data

## Overview

Resolve the Exceptions-as-Data findings introduced with direct HTTP LLM providers while preserving provider error
details at the public invocation boundary.

## Motivation

Provider HTTP failures currently cross an internal boundary as thrown values. The repository standard requires expected
operational failures to remain explicit data so callers can classify and render them without exception control flow.

## Changes in Detail

- Parse JSON into either a body value or a canonical fault anomaly instead of throwing through local control flow.
- Classify non-success responses by HTTP status even when their bodies are malformed.
- Preserve parse operation, status, and exception provenance when wrapping anomalies in LLM failure responses.
- Add regression coverage for direct-provider and Ollama status and parse failures.

## Testing Plan

- Run focused LLM HTTP-provider tests.
- Run the Exceptions-as-Data scanner and verify these findings are removed.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration or rollout step is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Follow-up to #1420 and #1427.

## Checklist

- [x] Preserve provider error classification and diagnostics.
- [x] Add regression coverage for expected failure paths.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
