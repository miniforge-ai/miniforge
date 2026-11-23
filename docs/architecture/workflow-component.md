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

## run-pipeline Options

`run-pipeline` takes `[workflow input opts]`. These options are part of its
contract for anyone embedding the runtime:

- `:event-stream` is required when any phase has gates. Each gate decision is
  published on the stream as a `:gate/decision` event, and the phase advances
  only on an acknowledged publication. With no stream the decision is
  unacknowledged and the gated phase fails closed, so a gated workflow run
  without a stream always fails.
- `:retention` belongs to the stream, not to `run-pipeline`. A process that
  keeps one stream for days creates it with
  `(create-event-stream {:retention bounded-retention})`, which bounds the
  in-memory history. Sinks and subscribers still see every event. A one-shot
  run can leave it out.
- `:logger` is the run's structured logger, kept as `:execution/logger`. The
  default logger writes human-readable lines.
- `:policy-packs` is the vector of packs the pack gates evaluate. Without it,
  `:policy-verify` and `:policy-review` evaluate the shipped standards pack,
  and `:policy-pack` blocks.
- `:checkpoint/root` is where checkpoints are written. The default is the
  configured root, which defaults under `MINIFORGE_HOME` (otherwise `~/.miniforge`).
  Tests and embedders that must not touch it pass an explicit root.
  On POSIX filesystems, new checkpoint directories are private (`0700`).
  Existing directories retain their permissions, including read-only sharing.
  Writes require trusted directory ownership and protection against entry replacement throughout the resolved ancestry.
  Group/world-writable checkpoint directories are rejected; sticky ancestors such as `/tmp` remain supported.
  New files are private (`0600`); replacements preserve existing POSIX permission bits.
  A rejected checkpoint is reported through the runner's existing failure handling.

Options copied onto the context are listed in
`workflow.context/execution-passthrough-option-keys`. An option missing from
that list stays only in `:execution/opts`, so a phase or gate that reads the
top-level context key does not see it.

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

Chains and workflow families are discovered from every matching classpath resource root.
The kernel does not need to know which app owns each workflow family.

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
