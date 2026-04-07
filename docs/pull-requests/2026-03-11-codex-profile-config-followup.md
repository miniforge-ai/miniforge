<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: extract state profiles to resource-backed registries

## Overview

Moves DAG task lifecycle profiles out of kernel code and into classpath resources, while keeping the kernel responsible
only for profile schema, normalization, and resolution.

## Motivation

The previous extraction left `software-factory` and `etl` profile data embedded in the `dag-executor` kernel. That kept
project-specific configuration in code and made the kernel own app-layer semantics it should only consume.

## Changes in Detail

- Replaced hardcoded profile literals in `state_profile.clj` with a resource-backed registry loader.
- Added a kernel fallback registry that exposes only the generic `:kernel` profile.
- Added a workflow-layer registry overlay that provides `:software-factory` and `:etl` profile resources and sets the
  active default profile for the flagship app.
- Removed direct profile constant exports from the public DAG executor interface and replaced them with resource-backed
  resolver APIs.
- Added regression coverage to prove the active profile registry comes from classpath resources and still supports
  explicit `:kernel` runs.

## Testing Plan

- `bb test`
- `bb test:integration`

## Deployment Plan

Merge normally. This is an internal configuration-boundary refactor with no data migration.

## Related Issues/PRs

- Follow-up to PR #291

## Checklist

- [x] Move project-specific profile data out of kernel code
- [x] Keep kernel profile resolution generic
- [x] Preserve software-factory default behavior through app-layer resources
- [x] Keep ETL profile available without reintroducing kernel literals
- [x] Verify unit and integration coverage
