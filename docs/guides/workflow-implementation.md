<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Workflow Component Implementation Guide

This guide summarizes the current implementation shape of the shared
`workflow` component.

The goal of the component is to provide a generic workflow runtime that can be
composed into different applications. Workflow families, phase handlers, and
selection defaults are expected to come from app-owned components.

## Core Namespaces

### `workflow.loader`

Loads workflow definitions from classpath resources and caches resolved
definitions.

Use it when you need to:

- load a workflow by id and version
- inspect available workflow metadata
- validate that a workflow resource can be resolved in the active project

### `workflow.registry`

Discovers workflows from every `workflows/` root on the active classpath and
maintains the runtime registry.

Use it when you need to:

- initialize workflow availability for a project
- inspect visible workflow ids
- reason about workflow characteristics for fallback selection

### `workflow.configurable`

Executes workflow definitions through the generic runtime.

Important traits:

- phase behavior can be provided through `:phase-handlers`
- plan execution can delegate to the DAG orchestrator seam
- the runtime interprets workflow data rather than hardcoding one domain flow

### `workflow.trigger`

Provides generic event-trigger entrypoints.

Application layers can keep convenience aliases for domain-specific events, but
the shared interface should remain event-oriented rather than PR-oriented.

### `workflow.publish`

Provides generic publication helpers.

The shared runtime should prefer neutral sinks such as directories, while app
layers can wrap git, worktree, or PR-specific behavior outside the kernel.

## Implementation Principles

### Keep workflow families out of the kernel

The shared component should own:

- schemas
- loaders
- registry helpers
- execution runtime
- generic triggers and publishers

It should not own:

- software-factory workflow families
- app-specific workflow selection mappings
- PR-specific publication assumptions

### Treat workflow composition as classpath composition

A project becomes “the software factory,” “financial ETL,” or another vertical
by choosing which components and resources to load.

That means implementation work should prefer:

- resource-backed configuration
- provider-backed runtime seams
- generic fallback behavior

over hardcoded workflow ids in shared code.

### Pass app behavior in through context

When the runtime needs domain behavior, prefer inputs such as:

- `:phase-handlers`
- profile providers
- selection profile resources
- publisher implementations

This keeps the shared runtime stable while apps vary around it.

## Testing Strategy

### Shared runtime tests

Cover:

- workflow loading and validation
- DAG correctness checks
- registry discovery across multiple classpath roots
- generic publication and event-trigger behavior
- state profile resolution and execution semantics

### App-composition tests

Cover:

- app-owned workflow resources appearing in the shared registry
- app-owned selection profile mappings resolving to loaded workflows
- app-owned state profiles loading through provider seams
- app-specific publishers or phase handlers working through the shared runtime

### End-to-end proofs

At minimum, keep proof workflows for:

- a simple shared workflow such as `simple-v2`
- a non-software vertical workflow such as `financial-etl`
- the flagship app workflow family through app composition

The point is to prove the kernel works across more than one domain without
adding domain branches back into the kernel.

## Practical Checklist for New Work

When adding a new workflow capability:

1. Decide whether it is kernel behavior or app behavior.
2. If it is app behavior, put the data or implementation in an app-owned
   component.
3. Feed that behavior into the shared runtime through an existing seam.
4. Add regression coverage for the seam you touched.
5. Avoid reintroducing workflow ids or domain terms into shared code unless the
   feature is intentionally app-specific.

## Related Files

- `components/workflow/src/ai/miniforge/workflow/interface.clj`
- `components/workflow/src/ai/miniforge/workflow/registry.clj`
- `components/workflow/src/ai/miniforge/workflow/configurable.clj`
- `components/workflow/resources/workflows/README.md`
