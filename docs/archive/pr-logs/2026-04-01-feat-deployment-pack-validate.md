<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: deployment pack validate

## Layer

Application

## Depends on

- `feat/deployment-pack-deploy`

## Overview

Adds the validate phase interceptor for HTTP health checks and smoke tests after deployment.

## Motivation

Post-deployment verification should be reviewable on its own instead of being bundled into deploy or workflow wiring.

## Changes in Detail

- Add `validate.clj` with endpoint retries and smoke-test execution.
- Register `:validate` phase defaults.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after deploy and before workflow wiring.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branch: `feat/deployment-pack-workflow-wiring`

## Checklist

- [x] Scope limited to the validate phase interceptor
- [ ] `bb pre-commit` recorded
