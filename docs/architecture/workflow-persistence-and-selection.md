<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Workflow Persistence and Selection

This document describes the workflow configuration mechanics that are actually
implemented today.

It intentionally covers the concrete runtime path, not the older aspirational
story around rollout percentages, A/B promotion, or full meta-loop-driven
workflow optimization.

## What Exists Today

The current system supports four real configuration paths:

1. Classpath workflow resources
2. Heuristic-store workflow persistence
3. Local active-workflow selection in `~/.miniforge/workflows/active.edn`
4. App-owned logical selection profiles via `config/workflow/selection-profiles.edn`

These paths are implemented across:

- `components/workflow/src/ai/miniforge/workflow/loader.clj`
- `components/workflow/src/ai/miniforge/workflow/persistence.clj`
- `components/workflow/src/ai/miniforge/workflow/comparison.clj`
- `bases/cli/src/ai/miniforge/cli/workflow_selection_config.clj`

## Workflow Loading

Workflow loading is resource-first, store-second.

`workflow.loader/load-workflow` resolves a workflow in this order:

1. in-memory cache
2. classpath resources under `workflows/`
3. heuristic store via `ai.miniforge.heuristic.interface/get-heuristic`

That means:

- shipped workflows come from whichever components contribute `workflows/*.edn`
  to the active classpath
- dynamically saved workflows can still be loaded from the heuristic store
- cache can be bypassed with `:skip-cache?`
- validation can be bypassed with `:skip-validation?`

## Active Workflow Persistence

The active workflow mapping is still implemented as a local file:

```text
~/.miniforge/workflows/active.edn
```

The current on-disk shape is:

```clojure
{:feature {:workflow-id :standard-sdlc
           :version "2.0.0"}
 :bugfix  {:workflow-id :quick-fix
           :version "2.0.0"}}
```

The API surface is:

- `workflow.persistence/get-active-workflow-id`
- `workflow.persistence/set-active-workflow`

Those functions are re-exported through the public workflow interface.

This is real, implemented local configuration. It is not the same thing as the
app-owned logical selection-profile mapping described below.

## Event Log Persistence

Workflow event logs are persisted locally under:

```text
~/.miniforge/workflows/events/<workflow-id>.edn
```

The implemented helpers are:

- `workflow.persistence/append-event`
- `workflow.persistence/load-event-log`

These support replay and determinism checks through the shared workflow
interface.

## Saving Workflows to the Heuristic Store

Workflow configurations can be saved into the heuristic store with:

- `workflow.comparison/save-workflow`
- public alias: `workflow.interface/save-workflow`

The saved heuristic key is derived from the workflow id:

```clojure
(keyword "workflow" (name (:workflow/id workflow)))
```

This is implemented today and is useful for:

- persisting generated or revised workflow definitions
- loading saved workflows later through the normal loader path

What is not implemented here is the larger lifecycle implied by older docs:

- no rollout percentage system
- no automated promotion logic
- no built-in experiment coordinator

## Comparing Workflow Executions

Execution comparison is also implemented today.

- `workflow.comparison/compare-workflows`
- public alias: `workflow.interface/compare-workflows`

It currently produces:

- per-execution summaries
- aggregate counts
- average tokens
- average cost
- average duration

This is a lightweight comparison utility, not a full experimentation framework.

## Logical Selection Profiles

Logical workflow selection profiles are resolved in the CLI layer through:

```text
config/workflow/selection-profiles.edn
```

The implemented behavior is:

1. merge all matching classpath resources
2. resolve a logical profile such as `:fast` or `:comprehensive`
3. if the configured workflow id is not present, fall back to generic workflow
   characteristics

That fallback uses:

- phase count
- max iterations

So the system currently supports both:

- explicit app-owned mapping
- generic fallback when no mapping is available

## Important Boundary Distinction

There are two different selection layers:

### Local active workflow mapping

Stored in `~/.miniforge/workflows/active.edn`

- local mutable runtime state
- keyed by task type
- stores concrete `{:workflow-id :version}` values

### App-owned logical selection profiles

Loaded from `config/workflow/selection-profiles.edn`

- classpath configuration
- keyed by logical profile such as `:fast`
- maps to concrete workflow ids

The older documentation tended to blend these together. They are separate
mechanisms.

## What the Older Docs Overstated

Older workflow-configuration docs also described behavior that is not present as
a complete system today:

- rollout percentages
- A/B traffic routing
- automatic promotion of a proposed workflow version
- a complete meta-loop workflow optimization cycle

Those ideas may still be useful design direction, but they should not be
documented as current runtime behavior.

## See Also

- `docs/architecture/workflow-component.md`
- `docs/architecture/live-workflow-configuration.md`
- `components/workflow-software-factory/README.md`
