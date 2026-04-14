<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack workflow wiring

## Layer

Integration

## Depends on

- `feat/deployment-pack-validate`

## Overview

Wires the deployment phases into workflow resources, chain resources, and project dependencies.

## Motivation

The workflow surface area should land after the underlying phases are already reviewable and validated in smaller
slices.

## Changes in Detail

- Add workflow and chain resources for deployment-oriented SDLC flows.
- Add workspace and project dependency wiring for the new deployment components.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge last in the deployment-pack stack.

## Related Issues/PRs

- Parent feature: deployment pack

## Checklist

- [x] Scope limited to workflow and dependency wiring
- [ ] `bb pre-commit` recorded
