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
- **Completed specs** -> `work/archive/done/`

## Lifecycle

```text
work/                    # Active work inputs
  |-- my-feature.spec.edn  -> Execute with: miniforge run work/my-feature.spec.edn
       |
[Miniforge executes work]
       |
work/archive/done/       # Completed work archived
  |-- my-feature.spec.edn
```

## Archive

Specs are archived into subdirectories when they are no longer active:

- `archive/done/` -- Work completed and merged
- `archive/stale/` -- Specs with outdated assumptions, tech stack, or framing

## Current Specs

### Ready to Run
| Spec | Description | Priority |
|------|-------------|----------|
| `finish-event-telemetry.spec.edn` | Wire phase events in verify/review/release | High |
| `fix-artifact-persistence.spec.edn` | Fix artifact persistence bugs | High |
| `release-pr-quality.spec.edn` | Structured PR body templates | High |
| `pr-comment-monitoring.spec.edn` | Autonomous PR comment monitoring | Medium |
| `wire-self-repair-chain.spec.edn` | Connect self-healing to workflow execution | Medium |
| `control-plane-completion.spec.edn` | Finish control plane integration | Medium |
| `dashboard-production-ready.spec.edn` | Dashboard polish and production-readiness | Medium |
| `tool-registry-phase4-6.spec.edn` | Tool registry dashboard + CLI + agent integration | Medium |
| `configurable-spec-types.spec.edn` | Policy-pack spec type configuration | Low |
| `tui-decomposition-workflow.spec.edn` | TUI decompose command | Low |
| `tui-react-renderer.spec.edn` | Virtual widget tree + direct-write renderer | Low |
| `workflow-redesign-use-case-targeted.spec.edn` | Use-case specific workflow definitions | Low |

### Reliability Nines Series (rn-*)
16 interconnected specs for reliability engineering:
| Spec | Description |
|------|-------------|
| `rn-00-reliability-nines-dag.edn` | DAG orchestration for the series |
| `rn-01` through `rn-16` | Failure taxonomy, workflow tiers, events, SLI/SLO, degradation, evidence, autonomy, safe-mode, trust, validation, tool semantics, compensation, success predicates, tool response validation, evaluation pipeline, index quality |

### Not Started
| Spec | Description |
|------|-------------|
| `gitlab-support.spec.edn` | Git provider abstraction + GitLab adapter |
| `gitlab-support-tasks.spec.edn` | Task breakdown for GitLab support |

## Usage

### Run a workflow spec

```bash
bb miniforge run work/finish-event-telemetry.spec.edn
```

### Archive completed work

```bash
git mv work/completed-feature.spec.edn work/archive/done/
```

---

**This directory enables autonomous dogfooding:** Miniforge works on itself by
consuming specs from `work/` and producing changes to the codebase.
