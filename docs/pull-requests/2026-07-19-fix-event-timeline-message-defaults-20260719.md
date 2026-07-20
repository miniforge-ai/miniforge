<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Clarify event timeline message defaults

## Overview

Resolve the two Dewey 210 message-default findings in event timeline rendering with explicit nil and malformed-value
semantics.

## Motivation

Timeline renderers require strings, while incoming event maps may omit `:message` or carry a nil value. The fallback
must remain safe without relying on `or` for map defaults.

## Changes in Detail

- Normalize timeline messages through one string-only extraction helper, including tool-call argument fallback.
- Normalize missing, nil, false, and malformed messages to empty text before applying renderer-specific fallbacks.
- Add regression coverage across phase-lifecycle, terminal, stall, generic, and tool-call renderers.

## Testing Plan

- Run focused event-stream timeline tests.
- Run the Dewey 210 scanner and verify two findings are removed.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Follows #1427 and #1430.

## Checklist

- [x] Preserve timeline rendering for absent and invalid messages.
- [x] Add regression coverage for selected findings.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
