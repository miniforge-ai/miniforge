<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# refactor: Name the pure OPSV assembly transitions

## Overview and motivation

The final #1957 standards pass found multi-step anonymous state transitions in
assembly allocation and accumulation. Extract a small pure transition vocabulary
so the atom-owning functions compose named operations without exceeding three strata.

## Changes in detail

Keep allocation, accumulation, diagnostics and atom ownership in the assembly namespace.
Move reference normalization and conditional map updates into an internal pure namespace.
Preserve scalar and collection inputs, immutable finalized records, missing-record
behavior and collision retry. No public API or persistence contract changes.
The assembly ordering test also uses named fixture transformations and finalization
instead of multi-step let bindings and an anonymous finalizer.

## Testing plan

Cover missing and finalized records, reference normalization, duplicate references
and identifier collision. Run deployed evidence consumers serially, rebuild the
CLI and run packaged assembly tests. Require zero standards violations and normal
signed hooks. Review the concrete transitions, not just the moved lines.
All three deployed evidence consumers pass serially. The rebuilt CLI passes
17 tests and 70 assertions, including inactive records that must not read material.

## Deployment plan

Ship with the normal CLI build after exact-head review and all CI including Build.
Preserve the branch and worktree. No stored data migration is needed.

## Related work

This independently reviewable prerequisite keeps #1957 within its PR budget.

## Checklist

- [x] Pure transition tests and rebuilt consumer acceptance
- [ ] Standards adversarial pass, signed hooks and exact-head review/CI
