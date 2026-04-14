<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack config

## Layer

Foundations

## Depends on

- `main`

## Overview

Adds the deployment pack configuration foundations: deployment defaults, message catalog wiring, schema predicates, and
GCP Secret Manager config resolution.

## Motivation

The deployment phases need typed configuration and deterministic config expansion before any deployment safety or
runtime orchestration can exist.

## Changes in Detail

- Add `phase-deployment` component dependencies and deployment config resources.
- Add `deploy/messages` translator wiring.
- Extend the schema interface with result helpers used by deployment code.
- Add `config_resolver.clj` and focused resolver tests.

## Testing Plan

- Run `bb pre-commit`

## Validation

- `bb pre-commit` passed

## Deployment Plan

- Merge first in the deployment-pack stack.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branches: `feat/deployment-pack-safety-gates`, `feat/deployment-pack-shell`

## Checklist

- [x] Scope limited to configuration foundations
- [x] Tests added for resolver behavior
- [x] `bb pre-commit` recorded
