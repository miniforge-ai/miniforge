<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Progress Review — 2026-04-13

> Checkpoint: spec completeness, OSS readiness, and next priorities.

**Baseline:** commit HEAD on `main`, 530 merged PRs, 87 components, 641 source
files (187k LOC), 313 test files (71k LOC).

---

## 1. Spec Completeness (N1–N11)

| Spec | Area | Est. % | Status Summary |
|------|------|--------|----------------|
| **N1** | Core Architecture | ~80 | Structure complete. Wiring gaps: not all state transitions emit events; evidence auto-gen on workflow completion needs verification; trust enforcement end-to-end unverified. |
| **N2** | Workflow Execution | ~85 | Phases, inner loop, DAG, gates all functional. Budget enforcement, capability contracts, and resumption completeness need verification. |
| **N3** | Event Stream | ~50 | Core infra solid. Many event types not yet emitted: gate, tool, inter-agent, milestone, OPSV, external PR, ETL, listener. Winsock vs HTTP/SSE decision pending. |
| **N4** | Policy Packs & Gates | ~55 | Pack schema, check/repair, severity work. Missing: K8s diff parsing, knowledge-safety pack (prompt injection), pack dependency graph, OPSV gates, external PR read-only eval. |
| **N5** | CLI / TUI / API | ~45 | CLI base and TUI engine exist. Many commands not wired to components. Winsock channel vs REST API needs resolution. TUI views not fully connected to live event streams. |
| **N6** | Evidence & Provenance | ~60 | Core bundle + provenance chain functional. Missing: sensitive data scanning (AWS keys, PII), compliance metadata, OPSV/control/external-PR evidence types. |
| **N7** | OPSV | ~0 | **Entirely unimplemented.** Largest single gap. No experiment packs, convergence loop, verification suite, or OPSV-specific gates. Infrastructure exists to support it. |
| **N8** | Observability Control (OCI) | ~10 | Event subscription exists. No RBAC, multi-party approval, privacy levels, retention policies, OTel alignment, W3C Trace Context, or listener budget controls. |
| **N9** | External PR Integration | ~35 | PR trains + repo DAG foundations exist. Missing: full PR Work Item model (readiness/risk/policy), automation tiers, provider-native check runs, read-only policy eval, credential management. |
| **N10** | Governed Tool Execution | ~50 | Tool registry exists. Enforcement gaps around sandboxing, approval flows, and audit logging for tool invocations. |
| **N11** | Task Capsule Isolation | ~50 | DAG executor has isolation primitives. In-progress work on artifact export, cleanup reliability, and timeout enforcement (4 active specs). |

### Overall: ~45–50% spec complete

**What works end-to-end today:** Core SDLC pipeline for Clojure projects — spec
in, PR out. DAG orchestration with parallelism. Intelligent model selection
(16 models, 4 providers). PR lifecycle management. Meta-agent learning loop.

---

## 2. OSS Readiness

### Present

| Artifact | Notes |
|----------|-------|
| LICENSE (Apache 2.0) | Full text, correct |
| README.md | Comprehensive overview |
| CONTRIBUTING.md | Setup, golden rules, PR process |
| CODE_OF_CONDUCT.md | Contributor Covenant v2.1 |
| SECURITY.md | security@miniforge.ai, 48hr SLA |
| PR Template | `.github/PULL_REQUEST_TEMPLATE.md` |
| CI/CD | GitHub Actions: lint → test → build |
| CHANGELOG.md | Keep a Changelog format |
| Pre-commit hooks | Enforced, never skipped |
| Linting | clj-kondo + markdownlint |

### Missing or Limited

| Item | Risk | Notes |
|------|------|-------|
| **Test coverage gaps** | **High** | PR lifecycle has zero tests. 6 high-priority integration tests identified in `TEST_OPPORTUNITIES.md`. |
| **No ROADMAP.md** | Medium | Roadmap lives in specs; not surfaced for contributors. |
| **No published packages** | Medium | No artifact on Clojars or Maven Central. |
| **No CLA / DCO** | Low–Medium | No contributor license agreement process. |
| **Docs discoverability** | Medium | Scattered across `docs/`, `specs/normative/`, `specs/informative/`, root-level markdown. |
| **No MAINTAINERS.md** | Low | Single maintainer implied. |

### Overall: ~75% OSS ready

Primary blocker is test coverage. All governance artifacts are in place.

---

## 3. Development Health

- **530 PRs merged**, 94 commits on main
- **Active development:** commits every few days, disciplined small PRs
- **Dogfooding daily:** meta-loop running on own codebase
- **Recent focus areas:**
  - Structured exception handling (slingshot adoption, 30 sites)
  - Event stream transit wire format
  - Runner refactoring (747 → 363 lines)
  - Meta-agent learning loop (N1 §3.3)
  - Standards violation remediation
  - TUI workstream specs

---

## 4. Work Spec Inventory

### In-Progress (4 specs)

| Spec | Area |
|------|------|
| `capsule-artifact-export` | N11 — artifact export before capsule destroy |
| `capsule-cleanup-reliability` | N11 — reliable cleanup on failure paths |
| `capsule-timeout-enforcement` | N11 — self-terminating on timeout expiry |
| `evidence-execution-mode` | N11 — record execution mode in evidence |

### Backlog — Ready to Run (12 specs)

| Spec | Area | Priority |
|------|------|----------|
| `release-pr-quality` | Release — PR description generation | High |
| `pr-comment-monitoring` | PR — autonomous comment resolution | Medium |
| `wire-self-repair-chain` | Self-healing → workflow wiring | Medium |
| `control-plane-completion` | Control plane integration | Medium |
| `dashboard-production-ready` | Web dashboard polish | Medium |
| `tool-registry-phase4-6` | Tool registry dashboard/CLI/agent | Medium |
| `configurable-spec-types` | Policy-pack spec type config | Low |
| `tui-decomposition-workflow` | TUI decompose command | Low |
| `tui-react-renderer` | Virtual widget tree renderer | Low |
| `workflow-redesign-use-case-targeted` | Use-case specific workflows | High |
| `standards-pass` | Apply always-apply rules | Medium |
| `migrate-runner-println-to-structured-logging` | Structured logging | Low |

### Backlog — Reliability Nines (16 specs)

`rn-01` through `rn-16` covering failure taxonomy, workflow tiers, SLI/SLO,
degradation modes, outcome evidence, autonomy model, safe-mode, trust
boundaries, validation layers, tool semantics, compensation, success predicates,
tool response validation, evaluation pipeline, and index quality.

### Backlog — TUI Workstreams (5 specs)

`tui-ws1` through `tui-ws5`: supervisory domain, durable startup, monitor mode,
governance surface, attention intervention.

### Backlog — Other (9 specs)

| Spec | Area |
|------|------|
| `gitlab-support` / `gitlab-support-tasks` | GitLab MR lifecycle |
| `backend-failover` | LLM rate-limit failover |
| `behavioral-verification-monitor` | Behavioral verification |
| `capsule-runtime-spec-wiring` | N11 runtime spec wiring |
| `policy-pack-extensibility` | Policy pack as sole extension point |
| `policy-pack-configuration` | Per-repo/org policy config |
| `knowledge-mcp-query` | Agent-driven rule query via MCP |
| `repo-config-profile` | Three-layer configuration |
| `environment-promotion-pipeline` | Environment promotion |
| `pr-monitor-loop` | Continuous PR monitoring |
| `fleet-supervisory-deferred` | Fleet-scale deferred concepts |

---

## 5. Gap Analysis: Work Specs vs. Spec Vision

The following normative spec areas have **no corresponding work specs** and
represent the uncaptured work needed to reach full spec compliance.

### N3 — Event Stream Completeness

No work spec exists to emit the missing event types: gate/checked,
gate/failed, gate/repaired, tool/invoked, tool/completed,
inter-agent/message-sent, inter-agent/message-received, milestone/reached,
ETL events, OPSV events, listener events, external PR events. The existing
`finish-event-telemetry` spec (in `work/done/`) covered phase events only.

### N4 — Knowledge-Safety Pack

No work spec for prompt-injection detection, tripwire system, or trust
enforcement validation. The knowledge trust model (trusted/untrusted/tainted)
exists but enforcement is unverified.

### N4 — Kubernetes Diff Parsing

No work spec. Terraform plan parsing is implemented; K8s equivalent is absent.

### N5 — CLI Command Wiring Audit

No work spec to audit and wire all CLI commands specified in N5 to their
corresponding components. Many components exist but lack CLI exposure.

### N5 — Winsock vs HTTP/SSE Decision

No work spec to evaluate the winsock bidirectional channel against HTTP REST +
SSE requirements and implement the chosen approach.

### N6 — Sensitive Data Scanning

No work spec for credential/PII detection in evidence bundles (AWS keys,
passwords, SSNs, credit card numbers) before storage.

### N6 — Compliance Metadata

No work spec for PII tagging, retention policy fields, or compliance metadata
in evidence bundles.

### N7 — OPSV (Entire Spec)

No work specs whatsoever for Operational Policy Synthesis. This is the largest
gap: experiment packs, operational policy proposals, convergence loops,
verification suites, OPSV-specific gates, and APPLY_ALLOWED governance.

### N8 — OCI Governance Layer

No work specs for RBAC roles/permissions, multi-party approval, privacy levels,
retention policies, listener budget controls, annotations
(recommendation/warning/insight), or audit logging for control actions.

### N8 — OTel + W3C Trace Context

No work spec for OpenTelemetry alignment or W3C Trace Context propagation.

### N9 — PR Work Item Model

No work spec for the full N9 PR Work Item schema: deterministic readiness
computation, explainable risk assessment, automation tier enforcement (Tier 0–3).

### N9 — Provider-Native Checks

No work spec for publishing GitHub Check Runs from policy evaluation results.

### N9 — Credential Management

No work spec for credential encryption and rotation for provider integrations.

### N10 — Tool Execution Audit Trail

No work spec to ensure all tool invocations produce audit events and that
approval flows are enforced for sensitive tools.

### Test Coverage (OSS Blocker)

No work spec for the 6 high-priority integration tests identified in
`TEST_OPPORTUNITIES.md`: PR lifecycle, release executor, agent response parsing,
gate validation pipeline, metrics accumulation, evidence bundle assembly.

---

## 6. Priority Recommendations

### Tier 1 — Ship Blockers (OSS + Core Quality)

1. **Integration test coverage** — PR lifecycle (zero tests), release executor,
   gate pipeline
2. **N3 event type completeness** — unblocks N8 and full observability
3. **N5 CLI wiring audit** — make existing components accessible

### Tier 2 — Production Readiness

4. **N6 sensitive data scanning** — compliance requirement
5. **N8 OCI governance foundation** — RBAC + control action audit
6. **N9 PR Work Item model** — readiness/risk for external PR management
7. **N4 knowledge-safety pack** — prompt injection defense

### Tier 3 — Vision Completion

8. **N7 OPSV** — entire workflow (may be deprioritized if not launch-critical)
9. **N8 OTel alignment** — distributed tracing
10. **N9 provider-native checks** — GitHub Check Run publishing

---

---

## 7. Gap Specs Created

All 15 gap areas from Section 5 now have corresponding work specs in `work/`:

| Spec File | Gap Area |
|-----------|----------|
| `n03-event-type-completeness.spec.edn` | N3 missing event types |
| `n04-knowledge-safety-pack.spec.edn` | N4 prompt-injection / trust |
| `n04-kubernetes-diff-parsing.spec.edn` | N4 K8s diff parsing |
| `n05-cli-command-wiring.spec.edn` | N5 CLI wiring audit |
| `n05-http-api-decision.spec.edn` | N5 winsock vs HTTP/SSE |
| `n06-sensitive-data-scanning.spec.edn` | N6 credential/PII scanning |
| `n06-compliance-metadata.spec.edn` | N6 compliance metadata |
| `n07-opsv-workflow.spec.edn` | N7 OPSV skeleton + DISCOVER |
| `n07-opsv-converge-verify-actuate.spec.edn` | N7 remaining phases |
| `n08-oci-governance.spec.edn` | N8 RBAC + control governance |
| `n08-privacy-retention.spec.edn` | N8 privacy + retention |
| `n08-otel-trace-context.spec.edn` | N8 OTel + W3C Trace Context |
| `n09-pr-work-item-model.spec.edn` | N9 readiness / risk / tiers |
| `n09-external-pr-read-only-eval.spec.edn` | N9/N4 read-only policy eval |
| `n09-provider-native-checks.spec.edn` | N9 GitHub Check Runs |
| `n09-credential-management.spec.edn` | N9 credential encryption |
| `n10-tool-execution-audit.spec.edn` | N10 tool audit + approval |
| `oss-integration-test-coverage.spec.edn` | OSS integration tests |

All normative spec areas (N1–N11) now have work spec coverage. No uncaptured
gaps remain.

---

*Next checkpoint target: 2026-05-13 or after Tier 1 completion, whichever comes
first.*
