<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge Specification Index

**Version:** 0.7.0-draft
**Date:** 2026-04-23
**Status:** Living specification during OSS development

---

## Three-Product Architecture

These specifications define three products built on a shared kernel:

- **MiniForge Core** (N1-N6) — the governed workflow engine. These six core specs define the engine contract that all
  products must conform to.
- **Miniforge** (N1-N10) — the autonomous software factory for SDLC. Consumes Core plus extension specs N7-N10 for fleet
  operations, observability control, external PR integration, and governed tool execution.
- **Data Foundry** (N1-N6) — a generic ETL product. Consumes the same Core engine contract with domain-specific workflow
  packs and policy configurations.

---

## What miniforge Is (Canonical Description)

**miniforge** executes a **workflow DAG** (planner → implementer → tester → reviewer → release
manager) with an **inner validate/repair loop** and **explicit gates**
(lint/coverage/stratification/docs/policy/etc). It produces **evidence bundles** and
**artifacts with provenance**, while emitting an **append-only event stream** (agent status,
tool use, subagents, LLM calls, messages) so the CLI/TUI can show live progress and
drill-down without scraping logs.

**The interesting parts for experts:**

- **Event stream as product surface area** (not just logging): it powers UX,
  replay/debuggability, and later learning/analytics.
- **Evidence bundles + semantic intent validation** as the primitive that makes "autonomous"
  credible to platform/security teams.
- **High-throughput triage UI** optimized for 100+ PR/day (email-triage model + batch operations).

---

## Normative Specifications (MUST/SHALL)

These specifications define contractual requirements for miniforge implementations.
They use RFC 2119 terminology (MUST, SHALL, SHOULD, MAY).

**Core specs (N1-N6) — MiniForge Core contract.** Fundamental contracts for architecture, workflows, events, policy, UI,
  and evidence. These define the shared engine consumed by all products (Miniforge SDLC, Data Foundry, and any future
  product).

**Extension specs (N7+) — product-specific capabilities.** Cross-cutting capabilities that extend core specs. Currently
  these are Miniforge SDLC concerns (Fleet Mode, Control Interface, PR Integration, Governed Tool Execution); Data
  Foundry does not consume N7-N10.

### N1 — Core Architecture & Concepts ✅

**File:** [normative/N1-architecture.md](normative/N1-architecture.md)
**Status:** Complete
**Purpose:** Stable conceptual model and layering boundaries

Defines:

- Core nouns: workflow, phase, agent, subagent, tool, gate, policy pack, evidence bundle,
  artifact, provenance, workflow pack, capability, pack run
- **Repository Intelligence:** Repo Index, Context Pack, Range, Symbol, Edge, Coverage (§2.27–§2.30)
- **Context Assembly:** tool contract (repo.map/search/symbol/open, nav.def/refs/impls/calls),
  staleness detection, policy envelopes, budget enforcement (§11)
- Three-layer architecture: Control Plane, Agent Layer, Learning Layer
- Polylith component boundaries (OSS component catalog)
- Operational model: local-first execution, reproducibility, failure semantics
- Agent protocols: communication patterns, context handoff, inter-agent messaging
- **Reliability Model:** Canonical failure taxonomy, SLIs/SLOs, error budgets, degradation modes (§5.3.3, §5.5)
- **Unified Autonomy Model:** A0-A5 levels with cross-spec mapping (§5.6)
- **Trust Boundary Validation:** 5 named boundaries with architectural invariants (§5.7)
- **Evaluation Pipeline:** Golden sets, replay mode, shadow mode, canary deployment (§3.3.3)

### N2 — Workflow Execution Model ✅

**File:** [normative/N2-workflows.md](normative/N2-workflows.md)
**Status:** Complete
**Purpose:** Engine contract for work representation and execution

Defines:

- Phase graph: Plan → Design → Implement → Verify → Review → Release → Observe
- Phase responsibilities: detailed requirements for each phase (inputs, outputs, gates)
- Inner loop: validate → feedback → repair → re-validate with multi-strategy repair
- Outer loop: phase transition state machine with prerequisites and failure handling
- Gate contract: check/repair function signatures, violation schema, enforcement rules
- Context handoff: protocol for passing context between phases
- Workflow chaining: typed outputs, input binding, cross-boundary provenance
- **Workflow tier:** `:best-effort` / `:standard` / `:critical` with tier-dependent SLO targets (§9.1)
- **Node capability extensions:** Idempotency keys, success predicates, compensation protocol (§13.6)

### N3 — Event Stream & Observability Contract ✅

**File:** [normative/N3-event-stream.md](normative/N3-event-stream.md)
**Status:** Complete
**Purpose:** **Most leverageful spec** - everything UI/analytics/learning builds on this

Defines:

- Event envelope fields; required event types (workflow, agent, status, subagent, tool,
  LLM, messages, milestone, gate, pack lifecycle, pack run, chain edge)
- Ordering guarantees (per-workflow sequence, causal ordering, replay determinism)
- Streaming API (SSE/WebSocket) with subscription protocol
- Throttling and performance requirements
- Minimal fields needed to render "live" progress and drill-down
- **Reliability metric events:** SLI computation, SLO breach, error budget, degradation mode (§3.17)
- **Repository intelligence events:** Index quality, canary failure (§3.18)
- **Failure class enum** on all failure events (`:failure/class`, see N1 §5.3.3)

### N4 — Policy Packs & Gates Standard ✅

**File:** [normative/N4-policy-packs.md](normative/N4-policy-packs.md)
**Status:** Complete
**Purpose:** Make policy-as-code real and pluggable

Defines:

- Pack structure: schema, versioning, signature requirements, rule definitions
- Gate execution contract: check/repair function interfaces with complete protocols
- Semantic intent validation: IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE rules
- Violation schema: severity levels, remediation templates, auto-fix capabilities
- Terraform/Kubernetes-specific validation rules
- Pack trust, capability grant, and high-risk action gates for Workflow Packs
- **Validation Layer Taxonomy:** L0 Syntax → L1 Semantic → L2 Policy → L3 Operational → L4 Authorization (§3.4)

### N5 — Interface Standard: CLI/TUI/API ✅

**File:** [normative/N5-cli-tui-api.md](normative/N5-cli-tui-api.md)
**Status:** Complete
**Purpose:** User-facing control plane surface area

Defines:

- CLI command taxonomy: seven namespaces (init, workflow, fleet, policy, evidence, artifact, pack)
- TUI primitives: workflow list, detail view, evidence viewer, artifact browser, pack browser, run launcher
- API surface: minimal REST endpoints for workflow control, event streaming, evidence/artifact access
- Operations console purpose: monitoring autonomous factory (NOT PR management)
- Manual override mechanisms: plan approval, gate handling, budget escalation

### N6 — Evidence & Provenance Standard ✅

**File:** [normative/N6-evidence-provenance.md](normative/N6-evidence-provenance.md)
**Status:** Complete
**Purpose:** Credibility - prove what happened and why it is safe

Defines:

- Evidence bundle schema: intent → phases → validation → outcome
- Artifact provenance: source inputs, tool executions, content hashes, timestamps
- Semantic intent validation rules with Terraform/Kubernetes specifics
- Queryable provenance API: trace artifact chains, find intent mismatches
- Pack Run evidence: pack identity, capabilities, connector actions, metrics snapshots, report artifacts
- Compliance metadata: sensitive data handling, audit requirements (SOCII/FedRAMP)
- **Reliability evidence:** SLI measurements, failure class, workflow tier, degradation mode in outcome (§2.6)
- **Evaluation artifacts:** Golden set and eval-run-result artifact types (§3.1.1)

### N7 — Operational Policy Synthesis With Verification ✅

**File:** [normative/N7-Operational-Policy-Synthesis.md](normative/N7-Operational-Policy-Synthesis.md)
**Status:** Complete
**Purpose:** Fleet Mode capability for governed experiments and policy synthesis

Defines:

- Experiment Pack schema: workload models, guardrails, convergence strategies
- Operational Policy schema: scaling signals, resource sizing, runtime guardrails
- OPSV workflow family: DISCOVER → PLAN → EXECUTE → CONVERGE → SYNTHESIZE → VERIFY → ACTUATE
- Verification requirements: pass/fail semantics, success criteria evaluation
- Fleet Mode integration: per-service policy state, experiment governance
- Risk scoring and actuation modes (RECOMMEND_ONLY, PR_ONLY, APPLY_ALLOWED)

### N8 — Observability Control Interface 🆕

**File:** [normative/N8-observability-control-interface.md](normative/N8-observability-control-interface.md)
**Status:** Draft
**Purpose:** Transform event stream into active control plane for agent fleets

Defines:

- Listener capability model: OBSERVE, ADVISE, CONTROL levels with RBAC
- Control action surface: pause, resume, rollback, quarantine, approve, emergency-stop
- Advisory annotation system: non-blocking recommendations and warnings
- Privacy and redaction: metadata-only, redacted, full privacy levels
- OpenTelemetry interoperability: GenAI span mapping, OTLP export
- Cost and volume controls: sampling rules, aggregation boundaries
- Fleet and enterprise extensions: multi-tenancy, pattern detection
- CLI/TUI extensions: listener commands, control palette, approval queue
- **Safe-mode posture:** Triggers, behavior, exit protocol for system-wide autonomy demotion (§3.4)

### N9 — External PR Integration 🆕

**File:** [normative/N9-external-pr-integration.md](normative/N9-external-pr-integration.md)
**Status:** Draft
**Purpose:** Treat external PRs as first-class Fleet Mode work items with monitoring, policy, and governance

Defines:

- PR Work Item model: canonical representation of any PR (external or Miniforge-originated)
- Provider ingestion: webhook/polling normalization from GitHub/GitLab to N3 events
- Readiness computation: deterministic merge-readiness from CI, reviews, conflicts, policy
- Risk assessment: explainable risk scoring as N6 evidence artifacts
- Policy evaluation: N4 policy packs applied to external PR diffs with provider feedback
- Automation tiers: Observe/Advise/Converse/Govern as N8 capability level specializations
- PR trains: explicit dependency ordering with governed merge sequencing
- Multi-repo configuration: per-repo opt-in with org-level defaults
- Fleet Mode disambiguation: N9 (SDLC governance) vs N7 (runtime policy synthesis)
- CLI/TUI/API extensions: `fleet prs`, `fleet trains` commands and views

### N10 — Governed Tool Execution 🆕

**File:** [normative/N10-governed-tool-execution.md](normative/N10-governed-tool-execution.md)
**Status:** Draft
**Purpose:** Safe, bounded, auditable execution of tool actions against external systems

Defines:

- Operational intent model: agents express intent, not commands; compiled to Operational IR
- Action classification (A-E): tool-declared risk levels from observational to irreversible
- Verification pipeline: target resolution, policy evaluation (N4), rollback verification
- Validation requirements: static analysis (all), provider dry-run (Class C+), adapter hooks
- Capability model: ephemeral, scoped, TTL-bounded, revocable authority grants
- Execution capsules: sandboxed runtime with filesystem, network, and time isolation
- Crown jewel protection: separation of authority, no autonomous mutation
- Postcondition monitoring: expected outcome verification with auto-rollback
- Safety invariants: ten mechanically-enforced rules preventing catastrophic operations
- External system integration: MCP servers and SaaS platforms as tool-registry entries
- Trust level progression (L0-L4): progressive autonomy gated by demonstrated safety
- Audit integration: full event stream (N3) and evidence bundle (N6) linkage
- **Tool operational semantics:** Timeout, retry, circuit-breaker, concurrency, fallback (§3.4–§3.5)
- **Tool response validation:** Schema validation and injection sanitization at capsule boundary (§7.4)

---

## Informative Documentation (Non-Normative)

These documents provide guidance, examples, and context but do NOT define contractual requirements.

### UX References

- [informative/ux-tui-mockups.md](informative/ux-tui-mockups.md) - Visual design for CLI/TUI (informs N5)
- [informative/ai-ux-flows.md](informative/ai-ux-flows.md) - AI-powered features (informs N3, N5)

### Guides (How-To)

- [informative/getting-started.md](informative/getting-started.md) - First workflow guide
- [informative/authoring-policies.md](informative/authoring-policies.md) - Policy pack development
- [informative/writing-workflows.md](informative/writing-workflows.md) - Workflow spec authoring
- [informative/building-scanners.md](informative/building-scanners.md) - Scanner development

### Vision & Positioning

- [informative/operational-modes.md](informative/operational-modes.md) - OSS vs Paid operational differences

> Product strategy documents (pricing, roadmap, competitive positioning) are maintained
> in the private [miniforge-fleet](https://github.com/miniforge-ai/miniforge-fleet) repository.

### Future Workflows

- [informative/pr-monitoring-workflow.md](informative/pr-monitoring-workflow.md) - PR monitoring and conflict resolution

### Demo Scripts

- [informative/yc-mvp-demo-script.md](informative/yc-mvp-demo-script.md) -
  YC-ready MVP demo narrative, flow, and implementation breakdown

### Architecture & Internals

- [informative/I-ANOMALY-SYSTEM.md](informative/I-ANOMALY-SYSTEM.md) - Canonical error representation and boundary
  translators
- [informative/I-DAG-ORCHESTRATION.md](informative/I-DAG-ORCHESTRATION.md) - DAG executor with PR lifecycle
- [informative/I-DAG-MULTI-PARENT-MERGE.md](informative/I-DAG-MULTI-PARENT-MERGE.md) - v2 of per-task base
  chaining: deterministic octopus merge of multi-parent task bases
- [informative/I-TASK-EXECUTOR.md](informative/I-TASK-EXECUTOR.md) - DAG-to-PR lifecycle integration

### Operational Workflows (N10 Extensions)

- [informative/I-VALIDATION-STRATEGIES.md](informative/I-VALIDATION-STRATEGIES.md) -
  Extended validation: formal verification, Shipyard, Tonic, canary execution
- [informative/I-INCIDENT-DIAGNOSTICS.md](informative/I-INCIDENT-DIAGNOSTICS.md) -
  Autonomous incident diagnostics and response workflow patterns

### Roadmaps (Experimental/Future)

- [informative/learning-meta-loop.md](informative/learning-meta-loop.md) - Future learning system (post-OSS)

---

## Examples (Reference Implementations)

Concrete examples that demonstrate compliance with normative specs.

### Workflow Examples

- [examples/workflows/rds-import.edn](examples/workflows/rds-import.edn) - Import existing RDS to Terraform
- [examples/workflows/k8s-deployment.edn](examples/workflows/k8s-deployment.edn) - Deploy to Kubernetes
- [examples/workflows/vpc-network-changes.edn](examples/workflows/vpc-network-changes.edn) - Network infrastructure

### Evidence Bundle Examples

- [examples/evidence/rds-import-bundle.edn](examples/evidence/rds-import-bundle.edn) - Complete evidence bundle
- [examples/evidence/semantic-validation.edn](examples/evidence/semantic-validation.edn) - Intent validation example

### Policy Pack Examples

- [examples/policy-packs/terraform-aws/](examples/policy-packs/terraform-aws/) - Terraform AWS safety checks
- [examples/policy-packs/kubernetes/](examples/policy-packs/kubernetes/) - K8s manifest validation
- [examples/policy-packs/foundations/](examples/policy-packs/foundations/) - General code quality

---

## Deprecated Documents

Documents superseded by normative specs. Retained for reference during migration.

- [deprecated/BUILD_PLAN.md](deprecated/BUILD_PLAN.md) - Superseded by informative roadmap
- [deprecated/BUILD_PLAN_REVISED.md](deprecated/BUILD_PLAN_REVISED.md) - Content extracted to N2, N3, N6
- [deprecated/OSS_PAID_ROADMAP.md](deprecated/OSS_PAID_ROADMAP.md) - Superseded; strategy docs moved to private repo
- [deprecated/REVISED_TIMELINE.md](deprecated/REVISED_TIMELINE.md) - Merged into roadmap
- [deprecated/AGENT_STATUS_STREAMING.md](deprecated/AGENT_STATUS_STREAMING.md) - Content extracted to N3

---

## Specification Governance

### Language Rules

**Normative specs (N1-N10):**

- MUST use RFC 2119 keywords: MUST, SHALL, SHOULD, MAY, MUST NOT, SHALL NOT
- MUST define versioning and compatibility expectations
- Breaking changes require version bump

**Informative docs:**

- MUST NOT use RFC 2119 keywords
- Use descriptive language
- Can change without version bump

### Amendment Process

**To add a new concept:**

1. It MUST land in N1 (glossary + concept model) first
2. Runtime data MUST land in N3 (events) or N6 (evidence/artifacts)
3. UX features MUST point to a contract in N3/N5/N6

**Extension specs (N7+):**

Extension specs define capabilities that span multiple core specs (N1-N6).
They MUST:

1. Explicitly define relationship to N1-N6 (what they extend)
2. Reference core specs rather than duplicate contracts
3. Add event types to N3, evidence to N6, commands to N5 as extensions
4. Define a Minimal Compliant Implementation (MCI)

**Rules to prevent spec explosion:**

1. **Core specs (N1-N6)** define fundamental contracts
2. **Extension specs (N7+)** define cross-cutting capabilities
3. **Every new concept must land in N1** or it's not real
4. **Every new runtime datum must land in N3 or N6** or it's unobservable
5. **Every UX feature must point to a contract** or it's just a mock
6. **Roadmaps never contain contracts** - they link to specs

### Conformance

Normative specs are enforced by:

- Schema validation tests (events/evidence/policy packs)
- Golden-file examples
- CLI contract tests
- Gate validation (specs enforced by gates)

---

## Implementation Notes

**Current focus:** N3 (Event Stream) and N6 (Evidence & Provenance) are foundational.

**Why start with N3 & N6?**

- Event stream powers UX, replay, debugging, and future learning
- Evidence bundles enable credibility and compliance
- These two specs force clarity across workflow engine, TUI, policy gates, and learning

**Implementation priority:**

1. N3 - Event stream protocol and emission from agents
2. N6 - Evidence bundle schema and provenance tracking
3. N5 - CLI/TUI interface consuming N3 events
4. N2 - Workflow execution model (already mostly implemented)
5. N4 - Policy pack standard (already mostly implemented)
6. N1 - Architecture (extracted from implementation)

---

## Version History

- **0.7.0-draft** (2026-04-23) - Pack interchange, control surface, and per-workflow streaming
  amendments. **N1**: Pack Signature Format (§2.10.4.1) and Pack Bundle Format (§2.10.6) so signed
  packs and pack archives are portable between OSS implementations; Tool Registry (§2.31) hoists
  the tool/connector contract from informative to normative so the capability-grant gate
  (N4 §5.1.9) has a canonical surface to enforce against. **N3**: §5.3 expanded from a one-line
  SSE sketch to a complete per-workflow wire contract (auth, listener attach handshake,
  filters, resume-from-sequence, backpressure, SSE/WebSocket formats, rate limiting). **N6**:
  `:pr-context-pack` artifact type registered. **N8**: Checkpoint Control (§3.1.5) and Model
  Control (§3.1.6) added to the control-action surface, with corresponding events in §10.
  **N9**: ingestion emission obligation for `:pr-context-pack`. **N11**: TaskExecutor Protocol
  (§10) hoisted to normative — pluggable substrate contract plus `persist-workspace!` /
  `restore-workspace!` for workspace handoff with reproducible digest; `:git` baseline kind only.
  Per-spec bumps: N1 0.5→0.6, N3 0.7→0.8, N6 0.5→0.6, N8 0.2→0.3, N9 0.1→0.2, N11 0.1→0.2
- **0.6.0-draft** (2026-03-08) - Reliability nines amendments: canonical failure taxonomy, SLIs/SLOs/error
  budgets, unified autonomy model (A0-A5), trust boundary validation, retrieval governance, evaluation
  pipeline in N1; workflow tier + compensation/success predicates in N2; failure class enum +
  reliability metric + repo intelligence events in N3; validation layer taxonomy in N4; SLI evidence +
  eval artifacts in N6; safe-mode posture in N8; tool operational semantics + response validation in N10
- **0.5.0-draft** (2026-03-04) - TUI fidelity amendments
- **0.4.0-draft** (2026-02-16) - OSS pack runtime amendments: Workflow Pack, Capability, Pack Run
  concepts in N1; workflow chaining in N2; pack lifecycle/run events in N3; pack trust/capability
  gates in N4; pack CLI + browser/launcher TUI in N5; Pack Run evidence in N6
- **0.3.0-draft** (2026-02-07) - Added N9 (External PR Integration), Fleet Mode disambiguation
- **0.2.0-draft** (2026-02-01) - Added N7 (OPSV) and N8 (OCI), updated governance for extension specs
- **0.1.0-draft** (2026-01-23) - Initial spec index, normative spec structure established
