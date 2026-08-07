<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: return cursor persistence failures as data

## Overview

Returns malformed persisted cursor content as a schema failure rather than throwing.

## Changes in Detail

- Make the shared disk-read helper return a schema result.
- Propagate failed reads through save and load operations.

## Testing Plan

- Cursor-store focused tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit gap fixed
- [x] Pre-commit checks passed
