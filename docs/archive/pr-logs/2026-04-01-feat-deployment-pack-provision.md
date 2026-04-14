<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack provision

## Layer

Application

## Depends on

- `feat/deployment-pack-evidence`

## Overview

Adds the provision phase interceptor for Pulumi preview and apply orchestration.

## Motivation

Provisioning infrastructure is the first deployment use-case and needs its own reviewable interceptor before the rest of
the pack is layered on.

## Changes in Detail

- Add `provision.clj` with preview analysis, apply flow, and evidence capture.
- Register `:provision` phase defaults.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after the foundations and before downstream deployment phases.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branches: `feat/deployment-pack-deploy`, `feat/deployment-pack-validate`

## Checklist

- [x] Scope limited to the provision phase interceptor
- [ ] `bb pre-commit` recorded
