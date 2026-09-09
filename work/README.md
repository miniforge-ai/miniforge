# work/

> These work specs drive **Miniforge SDLC** development -- they are consumed by the Miniforge autonomous factory to plan
and execute development tasks.

This directory contains **ephemeral workflow specifications and task definitions**
that are **inputs to miniforge** for active development work.

## What Goes Here

### Workflow Specifications (`.spec.edn`)

Miniforge workflow specs describing work to be done:

- Bug fixes
- Feature implementations
- Refactoring tasks
- Integration work

**Example:** `fix-artifact-persistence.spec.edn`

### Task Definitions (`.edn`)

DAG task definitions for parallel execution:

- Dogfooding task lists
- Multi-task development sessions

**Example:** `rn-00-reliability-nines-dag.edn`

## What Does NOT Go Here

- **Design specifications** -> `specs/informative/`
- **Normative requirements** -> `specs/normative/`
- **PR documentation** -> `docs/prs/` or `docs/pull-requests/`
- **Completed specs** -> `work/done/`

## Lifecycle

```text
work/                    # Active work inputs
  |-- my-feature.spec.edn  -> Execute with: miniforge run work/my-feature.spec.edn
       |
[Miniforge executes work]
       |
work/done/               # Completed work archived
  |-- my-feature.spec.edn
```

## Archive

Specs are archived into subdirectories when they are no longer active:

- `done/` -- Work completed and merged
- `archive/stale/` -- Specs with outdated assumptions, tech stack, or framing

## Current Work

[QUEUE.md](QUEUE.md) is the generated, authoritative inventory of active work
specs, organized by priority and theme. Regenerate it with `bb work:queue`
after adding, editing, completing, or archiving a work spec; do not duplicate
the inventory in this README.

## Usage

### Run a workflow spec

```bash
bb miniforge run work/finish-event-telemetry.spec.edn
```

### Archive completed work

```bash
git mv work/completed-feature.spec.edn work/done/
```

---

**This directory enables autonomous dogfooding:** Miniforge works on itself by
consuming specs from `work/` and producing changes to the codebase.
