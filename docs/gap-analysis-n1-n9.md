# Gap Analysis: Current State vs. Normative Specs (N1-N9)

**Date:** 2026-02-13
**Baseline commit:** 5c975b6 (+ PR #174 self-healing wiring)

## Legend

- **Implemented** — Component exists, core API functional, tests pass
- **Partial** — Component exists but missing key spec requirements
- **Stub/Minimal** — Component exists but needs significant work
- **Missing** — No implementation exists

---

## N1 — Core Architecture & Concepts

| Requirement | Status | Notes |
|---|---|---|
| Three-layer model (Control, Agent, Learning) | **Implemented** | orchestrator, agent, heuristic/knowledge |
| Polylith component boundaries | **Implemented** | 40 components, 2 bases, clean deps |
| Workflow UUID + lifecycle tracking | **Implemented** | workflow component |
| Agent protocol (invoke, get-status, abort) | **Implemented** | agent/protocols |
| Tool protocol (invoke, validate-args, get-schema) | **Implemented** | tool, tool-registry |
| Gate protocol (check, repair) | **Implemented** | gate component |
| Evidence bundle on completion | **Partial** | evidence-bundle exists; wiring to auto-generate on workflow completion needs verification |
| Event emission for all state transitions | **Partial** | event-stream exists; not all transition types may emit yet |
| Knowledge trust model (trusted/untrusted/tainted) | **Partial** | knowledge component exists; trust enforcement needs verification |
| Fail-safe/visible/recoverable/escalatable | **Partial** | Self-healing wired (PR #174); resume-from-phase needs verification |
| Local-only execution (no external deps except LLM) | **Implemented** | Polylith local deps, file-based storage |
| Deterministic reproducibility | **Partial** | workflow replay exists; full determinism needs verification |

**Gap summary:** Architecture is structurally complete. Main gaps are in
*wiring completeness* — ensuring every state transition emits events, every
workflow completion produces evidence, and the trust model is enforced
end-to-end.

---

## N2 — Workflow Execution Model

| Requirement | Status | Notes |
|---|---|---|
| Phase graph (Plan->Design->Implement->Verify->Review->Release->Observe) | **Implemented** | phase component with interceptors |
| Phase prerequisites validation | **Partial** | Need to verify pre-phase checks |
| Inner loop (Generate->Validate->Repair->Iterate) | **Implemented** | loop component |
| Inner loop retry budget (3-5 iterations) | **Partial** | Need to verify budget enforcement |
| Gate enforcement at phase boundaries | **Implemented** | gate component + check-gates-chain |
| Gate auto-repair on violations | **Implemented** | gate repair protocol |
| Semantic intent validation | **Implemented** | evidence-bundle has validate-intent + analyze-terraform-plan |
| DAG multi-task execution | **Implemented** | dag-executor with 7 layers, full state machine |
| Task states (pending->ready->...->merged/failed/skipped) | **Implemented** | dag-executor state machine |
| Frontier computation + skip propagation | **Implemented** | dag-executor scheduler |
| Resource locks for concurrent file access | **Implemented** | dag-executor lock management |
| Capability contracts (tool/path/knowledge scope) | **Partial** | Need to verify enforcement |
| Workflow resumption from last successful phase | **Partial** | Exists in workflow; completeness needs verification |
| Spec validation before execution | **Partial** | Need to verify all validations |

**Gap summary:** Execution model is well-implemented. Gaps are in
*enforcement completeness* — verifying budget limits, capability contracts,
and resumption are fully wired.

---

## N3 — Event Stream & Observability Contract

| Requirement | Status | Notes |
|---|---|---|
| Event envelope (type, id, timestamp, version, workflow/id, sequence-number) | **Implemented** | event-stream schemas |
| Append-only, immutable events | **Implemented** | event-stream component |
| Monotonic sequence numbers per workflow | **Partial** | Need to verify monotonicity guarantee |
| Workflow lifecycle events | **Implemented** | workflow-started/completed/failed |
| Phase lifecycle events | **Implemented** | phase-started/completed |
| Agent lifecycle events | **Partial** | agent-chunk, agent-status exist |
| LLM call events | **Implemented** | llm-request/response |
| Gate events | **Stub** | Need gate/checked, gate/failed, gate/repaired |
| Milestone events | **Stub** | Need milestone/reached |
| PR lifecycle events | **Partial** | task-executor has PR event translation |
| Task lifecycle events | **Partial** | dag-executor emits some events |
| ETL events | **Stub** | Not yet implemented |
| OPSV events | **Stub** | Not yet implemented |
| Listener events | **Stub** | Not yet implemented |
| External PR events | **Stub** | Not yet implemented |
| Inter-agent message events | **Stub** | Need inter-agent/message-sent/received |
| Tool use events | **Stub** | Need tool/invoked, tool/completed |
| SSE endpoint `/api/workflows/:id/stream` | **Partial** | Winsock bidirectional channel exists; SSE semantics need review |
| Subscription API | **Implemented** | subscribe!/unsubscribe! |
| Query API (history + filtering) | **Implemented** | event-stream query |
| Event validation against schema | **Partial** | Schema exists; validation on emit needs verification |
| <=10ms emission latency, >=100 subscriptions | **Partial** | Architecture supports it; not benchmarked |
| `:pr/id` correlation for external PRs | **Stub** | PR model exists; correlation key not yet standardized |

**Gap summary:** Core event stream infrastructure is solid. Main gaps:
(1) **many event types not yet emitted** (gates, tools, inter-agent, ETL,
OPSV, listeners, external PRs), (2) **review winsock channel vs HTTP/SSE
needs**, (3) event schema validation on emission.

---

## N4 — Policy Packs & Gates Standard

| Requirement | Status | Notes |
|---|---|---|
| Policy pack schema (id, version, rules, metadata) | **Implemented** | policy-pack component |
| Rule schema with severity levels | **Implemented** | hard-halt, require-approval, warn, audit |
| Check functions (pure, deterministic, <5s) | **Implemented** | policy-pack check-artifact |
| Repair functions (idempotent) | **Partial** | Gate repair exists; policy-level repair needs verification |
| Severity enforcement (critical blocks, high repairs) | **Partial** | hard-halt blocks; need to verify full severity cascade |
| Semantic intent validation rules | **Implemented** | evidence-bundle validate-intent |
| Terraform plan parsing | **Implemented** | evidence-bundle analyze-terraform-plan |
| Kubernetes diff parsing | **Stub** | Not found in codebase |
| Knowledge-safety pack | **Stub** | Knowledge trust model exists; full safety pack (tripwires, prompt injection detection) needs implementation |
| Pack dependency validation (cycles, versions, trust) | **Stub** | Need dependency graph for packs |
| Task scope enforcement pack | **Partial** | Capability contracts exist in dag-executor; need full policy pack wrapping |
| OPSV gates pack | **Stub** | Gates exist; OPSV-specific gates not yet implemented |
| External PR read-only evaluation | **Stub** | Policy pack exists; read-only mode for external PRs not yet implemented |
| Pack semantic versioning | **Partial** | Version field exists; enforcement needs verification |

**Gap summary:** Policy-pack and gate infrastructure exist. Main gaps:
(1) **Kubernetes diff parsing**, (2) **knowledge-safety pack** with full
tripwire detection, (3) **pack dependency management**,
(4) **OPSV-specific gates**, (5) external PR read-only evaluation mode.

---

## N5 — CLI/TUI/API

| Requirement | Status | Notes |
|---|---|---|
| CLI command structure `miniforge <ns> <cmd>` | **Partial** | CLI base exists; full command taxonomy needs verification |
| `miniforge init` | **Partial** | Likely exists; need to verify completeness |
| `miniforge workflow execute/status/list` | **Partial** | Workflow orchestration exists; CLI wiring needs verification |
| `miniforge fleet watch` (TUI) | **Partial** | tui-engine + tui-views exist |
| `miniforge fleet opsv` commands | **Stub** | OPSV not yet implemented |
| `miniforge listener` commands | **Stub** | OCI not yet implemented |
| `miniforge fleet prs` | **Stub** | PR model exists; CLI command needs verification |
| `miniforge policy list/install/show` | **Partial** | policy-pack exists; CLI commands need verification |
| `miniforge evidence show/export/list` | **Partial** | evidence-bundle exists; CLI commands need verification |
| `miniforge artifact provenance/list` | **Partial** | artifact store exists; CLI commands need verification |
| `miniforge etl repo` | **Stub** | Knowledge ETL needs verification |
| `miniforge pack list/show/promote` | **Stub** | Pack management needs verification |
| TUI 80x24 rendering | **Implemented** | tui-engine with Lanterna |
| TUI real-time updates | **Partial** | Event stream subscription exists; TUI wiring needs verification |
| TUI vim-style navigation | **Partial** | tui-engine supports keyboard; full vim bindings need verification |
| TUI views (Workflow, Evidence, DAG Kanban, etc.) | **Partial** | tui-views component exists |
| REST API / command channel | **Partial** | Winsock bidirectional channel exists; review sufficiency vs HTTP REST |
| SSE streaming | **Partial** | Winsock bidirectional may supersede one-way SSE; needs review |
| Config file + env vars | **Partial** | config component exists |

**Gap summary:** CLI base, TUI engine, and TUI views all exist. Main gaps:
(1) **review winsock channel sufficiency vs HTTP API**, (2) many CLI
commands may not be fully wired, (3) TUI views need to be connected to
live event streams.

---

## N6 — Evidence & Provenance

| Requirement | Status | Notes |
|---|---|---|
| Evidence bundle schema | **Implemented** | evidence-bundle component |
| Semantic validation (intent vs. behavior) | **Implemented** | validate-intent |
| Artifact provenance chain | **Implemented** | query-provenance, trace-artifact-chain |
| Terraform plan parsing | **Implemented** | analyze-terraform-plan |
| Immutable storage | **Partial** | File-based; immutability enforcement needs verification |
| Query API (by phase, type, workflow) | **Implemented** | evidence-bundle query functions |
| Sensitive data scanning | **Stub** | Not found; spec requires AWS key/password/SSN/credit card detection |
| Compliance metadata (PII, retention) | **Stub** | Not found |
| DAG run evidence | **Partial** | dag-executor exists; evidence emission needs verification |
| OPSV evidence | **Stub** | Not yet implemented |
| Control action evidence | **Stub** | Not yet implemented |
| External PR evidence | **Stub** | PR model exists; evidence artifact generation needs implementation |

**Gap summary:** Evidence bundle core is solid. Gaps:
(1) **sensitive data scanning**, (2) **compliance metadata**,
(3) OPSV/control/external-PR evidence types.

---

## N7 — Operational Policy Synthesis (OPSV)

| Requirement | Status | Notes |
|---|---|---|
| OPSV workflow phases (DISCOVER->...->ACTUATE) | **Missing** | No OPSV workflow implementation found |
| Experiment Pack schema | **Missing** | Not implemented |
| Operational Policy Proposal | **Missing** | Not implemented |
| OPSV gates (instrumentation, environment, blast-radius, abort, actuation, completeness) | **Missing** | Gate component exists but no OPSV-specific gates |
| Convergence loop | **Missing** | Not implemented |
| Verification suite (ramp, steady-state, stability) | **Missing** | Not implemented |
| APPLY_ALLOWED disabled by default | **Missing** | Not implemented |
| OPSV evidence per N6 | **Missing** | Not implemented |

**Gap summary:** **OPSV is entirely unimplemented.** This is the largest
single gap. The infrastructure exists (workflow, gates, evidence,
event-stream) but no OPSV-specific code has been written.

---

## N8 — Observability Control Interface (OCI)

| Requirement | Status | Notes |
|---|---|---|
| Listener registration (OBSERVE/ADVISE/CONTROL) | **Stub** | Event-stream has subscribe; no capability-level enforcement |
| RBAC roles and permissions | **Missing** | Not implemented |
| Control actions (pause/resume/retry/cancel) | **Partial** | dag-executor has pause/resume; no governed control action flow |
| Multi-party approval | **Missing** | Not implemented |
| Annotations (recommendation/warning/insight) | **Missing** | Not implemented |
| Privacy levels (metadata-only/redacted/full) | **Missing** | Not implemented |
| Retention policies | **Missing** | Not implemented |
| OTel alignment | **Missing** | Not implemented |
| W3C Trace Context propagation | **Missing** | Not implemented |
| Audit logging for all control actions | **Stub** | Evidence bundle exists; control action recording not wired |
| Listener budget controls | **Missing** | Not implemented |

**Gap summary:** **OCI is mostly unimplemented.** The event-stream
subscription mechanism provides the foundation, but the governance layer
(RBAC, capability enforcement, multi-party approval, privacy, retention)
is absent.

---

## N9 — External PR Integration

| Requirement | Status | Notes |
|---|---|---|
| PR Work Item model | **Partial** | pr-lifecycle + pr-train exist; full N9 schema (readiness, risk, policy, derived state) needs verification |
| Provider event normalization | **Partial** | task-executor has PR event translation |
| Readiness computation | **Stub** | PR state machine exists; deterministic readiness computation needs implementation |
| Risk assessment (explainable factors) | **Stub** | Not found |
| Policy evaluation on external PRs (read-only) | **Stub** | policy-pack exists; read-only mode not implemented |
| Automation tiers (Tier 0-3) | **Stub** | Not found |
| Provider-native checks (GitHub Check Runs) | **Missing** | Not implemented |
| PR trains with dependency ordering | **Implemented** | pr-train component |
| Train merge orchestration | **Partial** | pr-train has merge-next; tier constraints need verification |
| Idempotent event ingestion | **Partial** | Need to verify dedupe-key implementation |
| Audit logging for provider write actions | **Stub** | Not wired |
| Credential encryption + rotation | **Missing** | Not implemented |
| Per-repo config (`.miniforge/config.edn`) | **Stub** | Config component exists; per-repo overrides need verification |

**Gap summary:** PR train and repo DAG foundations exist. Main gaps:
(1) **full PR Work Item model** with readiness/risk/policy,
(2) **automation tier enforcement**,
(3) **provider-native check publishing**,
(4) **read-only policy evaluation**, (5) credential management.

---

## Priority Matrix — Next Work Items

### Tier 1: High-Impact Infrastructure (unblocks many other features)

| # | Work Item | Spec | Impact |
|---|---|---|---|
| **1** | **Review winsock channel vs HTTP API needs** | N3, N5, N8 | Determine if bidirectional winsock covers REST API + SSE or if HTTP layer still needed |
| **2** | **Complete event type emission** | N3 | Emit gate, tool, inter-agent, milestone, task lifecycle events from existing components |
| **3** | **Wire CLI commands to existing components** | N5 | Many components exist but CLI commands may not be fully connected |

### Tier 2: Core Governance (compliance + production-readiness)

| # | Work Item | Spec | Impact |
|---|---|---|---|
| **4** | **Listener capability enforcement (OCI foundation)** | N8 | OBSERVE/ADVISE/CONTROL levels on event subscriptions |
| **5** | **Control action governance** | N8 | RBAC, audit logging for pause/resume/cancel actions |
| **6** | **PR Work Item model + readiness/risk** | N9 | Full N9 schema, deterministic readiness, explainable risk |
| **7** | **External PR read-only policy evaluation** | N4, N9 | Evaluate policy packs against external PRs without repair |
| **8** | **Knowledge-safety pack** | N4 | Prompt-injection detection, trust enforcement, tripwires |

### Tier 3: Advanced Features

| # | Work Item | Spec | Impact |
|---|---|---|---|
| **9** | **OPSV workflow** | N7 | Entire experiment->converge->verify->actuate pipeline |
| **10** | **Sensitive data scanning** | N6 | Evidence bundle redaction before storage |
| **11** | **Privacy + retention policies** | N8 | Data lifecycle management |
| **12** | **Provider-native checks** | N9 | GitHub Check Run publishing |
| **13** | **Kubernetes diff parsing** | N4 | K8s policy enforcement |
| **14** | **OTel + W3C Trace Context** | N8 | Distributed tracing alignment |
