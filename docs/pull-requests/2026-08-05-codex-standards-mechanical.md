<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# chore: remediate mechanical standards violations

## Overview

Applies the mechanical results of the Miniforge standards audit.

## Changes in Detail

- Replaces nil-sensitive `or` defaults with `get` defaults.
- Regenerates the compiled Miniforge standards pack.
- Adds the required header to affected published documentation.

## Testing Plan

- `bb review`
- Normal pre-commit validation

## Deployment Plan

No runtime deployment; merge normally.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit complete
- [x] Pre-commit checks passed
