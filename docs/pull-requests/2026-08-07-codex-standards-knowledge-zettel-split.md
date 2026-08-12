<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: split zettel revision strata

## Overview

Brings `knowledge.zettel` within the three-layer stratified-design
budget without changing its public API.

## Changes in Detail

- Keep markdown serialization and link helpers in the public façade.
- Move content identity, digesting, revision stamping, and lifecycle
  updates into focused namespaces.
- Preserve all established `knowledge.zettel` function names as aliases.

## Testing Plan

- Staged stratum lint
- Focused knowledge component tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit gap fixed
- [ ] Pre-commit checks passed
