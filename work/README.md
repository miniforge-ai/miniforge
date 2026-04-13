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

### Normative Spec Gap Coverage (n0X-*)

Specs created 2026-04-13 to close identified gaps between work specs and
normative spec requirements (see `docs/progress-review-2026-04-13.md`):

| Spec | Normative | Description | Priority |
|------|-----------|-------------|----------|
| `n03-event-type-completeness` | N3 | Emit gate, tool, inter-agent, milestone events | High |
| `n04-knowledge-safety-pack` | N4 | Prompt-injection detection, tripwires, trust enforcement | High |
| `n04-kubernetes-diff-parsing` | N4 | K8s manifest diff parsing for policy gates | Medium |
| `n05-cli-command-wiring` | N5 | Audit and wire all N5-specified CLI commands | High |
| `n05-http-api-decision` | N5 | Resolve winsock vs HTTP/SSE, implement API layer | Medium |
| `n06-sensitive-data-scanning` | N6 | Credential/PII scanning in evidence bundles | High |
| `n06-compliance-metadata` | N6 | Data classification, retention, regulatory tags | Medium |
| `n07-opsv-workflow` | N7 | OPSV skeleton — DISCOVER phase + experiment packs | Low |
| `n07-opsv-converge-verify-actuate` | N7 | OPSV remaining phases (requires n07-opsv-workflow) | Low |
| `n08-oci-governance` | N8 | RBAC, listener capabilities, control actions, audit | Medium |
| `n08-privacy-retention` | N8 | Privacy levels, listener budgets, retention (requires n08-oci-governance) | Medium |
| `n08-otel-trace-context` | N8 | OpenTelemetry alignment, W3C Trace Context | Low |
| `n09-pr-work-item-model` | N9 | Readiness, risk assessment, automation tiers | Medium |
| `n09-external-pr-read-only-eval` | N9/N4 | Read-only policy evaluation for external PRs | Medium |
| `n09-provider-native-checks` | N9 | GitHub Check Runs from policy evaluation | Medium |
| `n09-credential-management` | N9 | Credential encryption at rest, rotation | Medium |
| `n10-tool-execution-audit` | N10 | Tool audit trail, risk classification, approval flows | Medium |
| `oss-integration-test-coverage` | OSS | 6 high-priority integration tests (TEST_OPPORTUNITIES.md) | High |

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
