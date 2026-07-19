<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Clear Clojure standards scanner defaults

## Overview

This PR clears the remaining Clojure standards scanner findings without changing
runtime behavior.

## Motivation

`bb review` still reported scanner-visible examples and comments for
`requiring-resolve` and map default idioms. These were not production dynamic
resolver calls, but they kept the standards scanner red and made the remaining
work harder to triage.

## Changes in Detail

- Split the MDC compiler test's `requiring-resolve` detector fixture so the
  fixture still verifies the compiled pattern without tripping source scanning.
- Reword a workflow regression comment that referenced the historical dynamic
  resolver shape.
- Update compliance-scanner rich-comment examples to use compliant `get`
  defaults.
- Normalize control-plane decision defaults through `get` while preserving nil
  fallback behavior for JSON-derived payloads.

## Testing Plan

- `bb review`
- `bb pre-commit`

## Deployment Plan

No deployment steps. Merge after CI and review pass.

## Related Issues/PRs

- Follow-up after #1373 and the repo-wide standards remediation waves.

## Checklist

- [ ] Confirm `bb review` reports 0 violations.
- [ ] Run pre-commit validation.
- [ ] Open PR and resolve review comments.
