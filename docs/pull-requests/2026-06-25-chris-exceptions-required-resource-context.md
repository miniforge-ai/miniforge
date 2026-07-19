<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Refactor: Preserve Context on Required Config Resources

## Overview

This PR extends the shared classpath config-resource loader so callers can
preserve additional ex-data context, then migrates remaining map-shaped
required resource loads through that shared boundary.

## Motivation

Earlier resource-loader waves centralized missing/malformed/non-map config
failures in `ai.miniforge.config`. Some remaining call sites still hand-roll
`io/resource` + `edn/read-string` only to preserve concrete packaging hints in
their thrown ex-data. The config loader should own that failure boundary while
still allowing callers to attach domain-specific diagnostic context.

## Changes in Detail

- Add optional ex-data context to `config.resource/load-config-resource`.
- Preserve classpath hint data for PR monitor defaults and Observe phase
  config.
- Move supervisory-state task Kanban mapping resource loading through the
  shared required config loader.
- Remove local EDN/resource parsing requires made obsolete by the migration.

## Testing Plan

- Focused config-resource tests.
- Focused namespace tests for observe phase, PR monitor defaults, and
  supervisory-state accumulator.
- `bb review` to confirm the exceptions-as-data count decreases.
- `bb pre-commit` before merge.

## Deployment Plan

No special deployment steps. The resource paths, config shapes, and existing
schema validation behavior are unchanged.

## Related Issues/PRs

- Follows PR #1273, which consolidated fail-open config loaders.

## Checklist

- [x] Preserve missing-resource hint ex-data.
- [x] Preserve schema validation for migrated resources.
- [x] Confirm focused tests pass.
- [x] Confirm `bb review` decreases.
- [x] Run `bb pre-commit`.
