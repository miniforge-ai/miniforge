<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack deploy

## Layer

Application

## Depends on

- `feat/deployment-pack-provision`

## Overview

Adds the deploy phase interceptor for rendering, applying, and observing Kubernetes deployment state.

## Motivation

The application deployment step should be reviewed independently from provisioning and validation logic.

## Changes in Detail

- Add `deploy.clj` with rollback snapshotting, kustomize render/apply, rollout wait, and pod-state capture.
- Register `:deploy` phase defaults.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after provision and before validate.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branch: `feat/deployment-pack-validate`

## Checklist

- [x] Scope limited to the deploy phase interceptor
- [ ] `bb pre-commit` recorded
