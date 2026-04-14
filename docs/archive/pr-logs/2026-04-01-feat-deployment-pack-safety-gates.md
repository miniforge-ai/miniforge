<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack safety gates

## Layer

Domain

## Depends on

- `feat/deployment-pack-config`

## Overview

Adds the deployment safety pack, custom policy checks, and deployment gate registrations.

## Motivation

Provision and validation phases need explicit policy and gate logic before they can safely operate against
infrastructure and clusters.

## Changes in Detail

- Add the `deployment-safety` pack resource.
- Add policy helper functions for Pulumi preview analysis.
- Add deployment gate registrations and focused policy tests.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after config foundations and before phase interceptors.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branches: `feat/deployment-pack-provision`, `feat/deployment-pack-validate`

## Checklist

- [x] Scope limited to safety policy and gate rules
- [x] Tests added for policy helpers
- [ ] `bb pre-commit` recorded
