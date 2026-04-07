<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Workflow Component Architecture

This document describes the current architecture of the shared `workflow`
component.

The component is the generic runtime for loading, validating, and executing
workflow definitions. It is not the home of every shipped workflow family.
Application-specific workflows are composed onto the classpath by whichever
project or base loads them.

## Overview

The shared `workflow` component provides the domain-neutral execution layer for
governed workflows.

**Key responsibilities:**

- Discover workflow definitions from classpath resources
- Validate workflow EDN against schema and DAG rules
- Execute workflow phases through the generic runtime
- Expose registry, loading, replay, event-trigger, and publication APIs
- Support app-owned composition for workflows, chains, and state profiles

## Component Structure

```text
components/workflow/
├── src/ai/miniforge/workflow/
│   ├── interface.clj       # Public Polylith boundary
│   ├── configurable.clj    # Config-driven workflow execution
│   ├── loader.clj          # Workflow loading and caching
│   ├── registry.clj        # Classpath discovery and registry
│   ├── trigger.clj         # Generic event-trigger entrypoints
│   ├── publish.clj         # Generic publication helpers
│   └── validator.clj       # Schema and DAG validation
├── resources/
│   ├── schemas/
│   │   └── workflow.edn
│   ├── config/
│   │   └── workflow/
│   │       └── state-profiles/
│   └── workflows/
│       ├── simple-v2.0.0.edn
│       └── test/demo workflows
└── test/
```

App-specific workflow families live in separate components such as:

- `components/workflow-software-factory/resources/workflows/`
- `components/workflow-financial-etl/resources/workflows/`
- `components/workflow-chain-software-factory/resources/chains/`
- app-owned `config/workflow/*.edn` resources

## Runtime Composition

The current model is classpath composition, not a single kernel-owned workflow
catalog.

```text
shared workflow component
  + shared reference workflows
  + app-specific workflow resources
  + app-specific chain resources
  + app-specific selection profile config
  + app-specific state profile config
  = runtime-visible workflow surface
```

In practice:

- the shared `workflow` component contributes reference workflows such as
  `simple-v2`
- an application component may add software-factory workflows such as
  `canonical-sdlc-v1`
- an analytical product component may add ETL workflows such as
  `financial-etl`
- the active project decides what exists by deciding which components are on
  the classpath

## Discovery and Registry

Workflow discovery is resource-driven.

- `workflow.registry/discover-workflows-from-resources` scans every
  `workflows/` resource root on the active classpath
- `workflow.loader/list-available-workflows` extracts metadata from those
  resources
- `workflow.registry/initialize-registry!` registers the discovered workflows

This is the seam that allows the kernel to stay generic while apps own their
workflow families.

## Execution Model

The shared runtime executes whichever workflow definition it is given.

- `workflow.configurable` interprets the workflow DAG
- phase behavior can be provided through `:phase-handlers`
- DAG execution can delegate to the `dag-executor` seam when a plan produces
  parallelizable work
- publication happens through generic publishers such as the directory
  publisher, while app layers can wrap git or PR-specific publication

The runtime should not assume software delivery as the only domain.

## Related App-Owned Configuration

The workflow runtime now expects several important choices to come from the app
layer.

### Workflow selection profiles

Logical selection profiles such as `:fast` and `:comprehensive` are resolved
through classpath resources like:

```text
config/workflow/selection-profiles.edn
```

If an app does not provide config, shared fallback logic uses generic workflow
characteristics rather than hardcoded software-factory workflow ids.

### DAG state profiles

Execution lifecycle profiles are loaded through provider-backed resources rather
than hardcoded kernel literals.

### Chains and workflow families

Chains and workflow families are discovered from every matching classpath
resource root, so the kernel does not need to know which app owns which
workflow family.

## What Belongs in the Shared Component

- Workflow loading, validation, execution, replay, and registry APIs
- Generic publication and event-trigger seams
- Shared reference workflows and test workflows
- Generic schemas and runtime helpers

## What Does Not Belong in the Shared Component

- Software-factory workflow families
- PR-specific chains and release flows
- App-specific workflow defaults or recommendation vocabulary
- Enterprise/Fleet control-plane behavior

## See Also

- `docs/architecture/live-workflow-configuration.md`
- `docs/architecture/workflow-persistence-and-selection.md`
- `docs/guides/workflow-implementation.md`
- `components/workflow/resources/workflows/README.md`
- `components/workflow-software-factory/README.md`
- `components/phase-software-factory/README.md`
