<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Classify MCP and Listener Boundary Namespaces

## Overview

This PR fixes exceptions-as-data boundary namespace classification for two
documented protocol edges that were still reported as violations.

## Motivation

The scanner documents boundary exemptions for namespaces containing segments
such as `mcp`, `http`, `listener`, `consumer`, and `boundary`. Two real repo
boundaries did not fit the existing exact-segment catalog:

- `ai.miniforge.mcp-context-server.*` is a base named with a dashed Polylith
  segment, so `mcp` is not a standalone dotted segment.
- `ai.miniforge.event-stream.listeners` uses the plural `listeners` segment.

This is scanner precision work, not a weakening of the rule: non-boundary
production namespaces remain subject to the same exceptions-as-data checks.

## Changes in Detail

- Add `listeners` to the explicit boundary segment catalog.
- Add a narrow exact-prefix exemption for `ai.miniforge.mcp-context-server`.
- Preserve the existing rule that boundary markers are not arbitrary
  substrings of component names.
- Add regression coverage for the MCP base, plural listeners, and non-boundary
  dashed names that merely contain boundary words.

## Testing Plan

- Focused exceptions-as-data scanner tests.
- `bb review` to confirm documented boundary false positives disappear.
- `bb pre-commit` before merge.

## Deployment Plan

No runtime deployment impact. This changes compliance scanner classification
only.

## Related Issues/PRs

- Follows PR #1274.

## Checklist

- [x] Preserve non-boundary detection.
- [x] Preserve component-name substring exclusion.
- [x] Exempt the MCP context-server base and plural listener boundary.
- [x] Confirm focused scanner tests pass.
- [x] Confirm `bb review` decreases.
- [x] Run `bb pre-commit`.
