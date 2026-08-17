<!--
  Title: Remove temporary deployment-authority compatibility
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(deploy): require canonical authority inputs

## Layer

Deployment domain authority.

## Overview

Remove migration fallbacks retained while the governed deploy caller was not
yet live. PR 1794 now resolves one exact target and supplies the canonical
execution identifier before authority preparation.

## Changes

- Require `:execution/id`; do not fall back to legacy `:run-id`.
- Require the caller-resolved `:context`; do not normalize `:context-name` or
  `:default-context` inside the authority boundary.
- Bind the issuance-required `:default-context` field to that same exact
  context.
- Delete the unused legacy render-only `preflight` function and its string
  dependency.
- Assert that legacy-only run and target inputs remain unbound.

## Verification

- `clojure -M:poly test brick:phase-deployment`
- `bb pre-commit`
- `bb review`
