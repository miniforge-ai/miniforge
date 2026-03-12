# Software Factory Workflow Chains

This component owns chain definitions for the flagship software-factory
application.

Chains are higher-level compositions that bind application inputs to one or
more workflow executions. They are app assets, not shared-kernel assets.

## Shipped Chains

| Chain ID | Version | Purpose |
|----------|---------|---------|
| `spec-to-pr` | 1.0.0 | Run the flagship spec-to-pull-request flow |

`spec-to-pr` currently delegates to the `standard-sdlc` workflow and binds
spec-style inputs into the workflow execution.

## What This Component Owns

- chain resources under `resources/chains/`
- software-factory chain semantics such as spec-to-PR orchestration

## Relationship to the Shared Workflow Component

The shared `workflow` component owns generic chain loading and listing.
This component owns the actual flagship chain resources.

## Related Components

- `components/workflow-software-factory/`
- `docs/architecture/live-workflow-configuration.md`
