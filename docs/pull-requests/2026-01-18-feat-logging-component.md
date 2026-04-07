<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add Logging Component with Structured EDN Logging

**PR**: [#2](https://github.com/miniforge-ai/miniforge/pull/2)
**Branch**: `feat/logging-component`
**Date**: 2026-01-18
**Status**: Merged

## Overview

Add the `logging` component providing structured EDN logging capabilities. This component depends on `schema` for type definitions and provides the logging infrastructure for all other components.

## Motivation

miniforge requires structured logging for:
- **Debugging**: Rich context in log entries
- **Traceability**: Correlation IDs across distributed operations
- **Auditing**: Immutable record of system actions
- **Performance**: Timing and metrics capture
- **Meta-loop**: Feeding data back into learning systems

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `components/logging/src/ai/miniforge/logging/core.clj` | Core logging implementation |
| `components/logging/src/ai/miniforge/logging/interface.clj` | Public API |
| `components/logging/deps.edn` | Component dependencies |

### Features

- Structured EDN log format
- Multiple log levels
- Correlation ID tracking
- Performance timing capture
- Context propagation
- Configurable outputs

## Testing Plan

- [x] Component compiles
- [x] Log entries validate against schema
- [x] Integration with schema component works

## Deployment Plan

Merged to main. Available for use by application components.

## Related Issues/PRs

- Depends on #1 (schema component)
- Implements spec from `docs/specs/logging.spec`
