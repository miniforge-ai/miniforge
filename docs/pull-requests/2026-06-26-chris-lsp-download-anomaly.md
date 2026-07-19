<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Return LSP download failures as data

## Overview

This PR converts the LSP auto-installer download helper from a thrown exception
to anomaly data. The GitHub installation pipeline now carries the download
anomaly directly instead of relying on a throw/catch hop.

## Motivation

The exceptions-as-data cleanup scan reports one throw site in the LSP installer.
A failed `curl` subprocess is an expected installation failure mode and can be
represented as an anomaly value in the existing installer result pipeline.

## Changes in Detail

- Return `:anomalies/fault` anomaly maps from `download-file` when `curl`
  exits non-zero.
- Have `step-download` propagate the returned anomaly and clean up its temp dir.
- Add focused unit coverage for direct download failures and GitHub installer
  propagation without network I/O.

## Testing Plan

- Run focused LSP installer tests.
  Latest result: 8 tests, 22 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `lsp-mcp-bridge.installer`
  contributes zero cleanup-needed rows.
  Latest scanner count: 153 cleanup-needed rows; `lsp-mcp-bridge.installer`
  contributes zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is an installer failure-contract cleanup.

## Related Issues/PRs

- Follows PR #1284.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
