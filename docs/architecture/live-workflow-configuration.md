# Live Workflow Configuration Architecture

This document explains how workflow configuration becomes live at runtime in the
current classpath-composed model.

The important change is that the shared runtime no longer owns a single
software-factory catalog. Workflow families, chains, selection profiles, and
state profiles are composed by the active project.

For the parts of workflow configuration that are concretely implemented today,
including `active.edn`, heuristic-backed workflow saving/loading, and logical
selection-profile resolution, see
`docs/architecture/workflow-persistence-and-selection.md`.

## Configuration Flow

```text
1. RESOURCES ON CLASSPATH
   - shared workflow resources
   - app-specific workflow resources
   - app-specific chain resources
   - app-specific config/workflow/*.edn resources
   - app-specific config/dag-executor/*.edn resources

2. DISCOVERY
   - workflow registry scans every workflows/ root
   - chain loader scans every chains/ root
   - selection config merges matching config resources
   - state profile provider resolves matching profile resources

3. RESOLUTION
   - caller selects a workflow id directly, or
   - app config maps a logical selection profile to a workflow id, or
   - fallback logic derives a workflow from generic characteristics

4. EXECUTION
   - workflow runtime loads the workflow definition
   - phase handlers come from execution context
   - publication and triggers use generic interfaces with app-owned adapters
```

## Shared vs App-Owned Resources

### Shared resources

The shared `workflow` component contributes:

- workflow schema
- reference workflows such as `financial-etl` and `simple-v2`
- generic runtime code

### App-owned resources

Applications contribute:

- additional workflow families
- chain definitions
- workflow selection profile config
- DAG state profile config
- phase implementations and workflow helpers

For example, the CLI app can load software-factory workflows without making the
kernel itself software-factory-specific.

## Workflow Discovery

Workflow discovery is driven by all `workflows/*.edn` resources on the active
classpath.

That means:

- a minimal project may only see shared reference workflows
- the flagship app may see both shared workflows and software-factory workflows
- a future analytical app can add its own workflows without modifying the
  shared runtime

## Selection Profile Resolution

Workflow selection now has two layers:

1. Logical profiles such as `:fast`, `:comprehensive`, and `:default`
2. App-owned mapping from those logical profiles to concrete workflow ids

The mapping comes from:

```text
config/workflow/selection-profiles.edn
```

If no app-specific mapping is present, fallback selection uses generic workflow
characteristics such as phase count and max iterations.

This keeps the selection seam generic even when the loaded app happens to be
the software factory.

## DAG State Profile Resolution

Workflow execution lifecycle semantics are also resource-driven.

- the kernel owns profile normalization and consumption
- profile definitions come from provider-backed resources
- projects can swap in different lifecycle semantics without editing kernel code

This is the seam that allows the same runtime to support both software-factory
flows and analytical flows such as financial ETL.

## Publication and Triggering

The runtime exposes generic seams for:

- event-triggered workflow starts
- output publication

The shared runtime ships with generic implementations such as directory
publication. App layers can add git, worktree, or PR-specific publication
without moving those assumptions back into the kernel.

## Why This Matters

This composition model supports the open-core boundary directly:

- OSS kernel owns the generic runtime and SDK seams
- flagship apps own their workflow families and app-specific defaults
- additional verticals can compose new resources onto the same runtime

## Practical Examples

### Shared-only project

- visible workflows: `financial-etl`, `simple-v2`, test/demo workflows
- publication: directory publisher
- selection fallback: based on generic workflow characteristics

### Flagship software-factory project

- visible workflows: shared workflows plus SDLC workflow families
- selection mapping: app config points `:fast` and `:comprehensive` at
  software-factory workflows
- publication: app can layer git or PR-specific publishing on top

### Analytical vertical project

- visible workflows: shared workflows plus analytical packs or demos
- state profile: app-specific lifecycle resource
- publication: local directories, reports, exports, or domain-specific sinks

## See Also

- `docs/architecture/workflow-component.md`
- `docs/architecture/workflow-persistence-and-selection.md`
- `components/workflow/resources/workflows/README.md`
- `components/workflow-software-factory/README.md`
- `components/workflow-chain-software-factory/README.md`
