<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge Specification Index

**Version:** 0.20.0-draft
**Date:** 2026-08-10
**Status:** Living specification during OSS development

---

## Three-Product Architecture

These specifications define three products built on a shared kernel:

- **MiniForge Core** (N1-N6 plus applicable indexed amendments) — the governed workflow engine contract shared by all
  products.
- **Miniforge** — the autonomous software factory for SDLC. Consumes Core plus the Miniforge-scoped extensions and
  amendments in the applicability table below.
- **Data Foundry** — a generic ETL product. Consumes Core plus only the indexed amendments that declare applicability to
  its workflow/runtime capabilities.

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

**Amendments and extension specs — scoped capabilities.** Indexed delta specs amend a named base contract. N7+ specs
  define product or capability extensions. Applicability is explicit rather than inferred from the filename alone.

### Applicability

| Spec set | Applies to |
|----------|------------|
| N1-N6 | Every product built on MiniForge Core |
| N2 checkpoint/resume delta | Implementations that persist workflow state |
| N4 policy-compilation delta | Implementations that originate compiled policy packs |
| N5 supervisory deltas | Miniforge SDLC control-plane producers and consumers |
| N7-N10 | Miniforge Fleet/SDLC capabilities |
| N11 + runtime-adapter delta | Governed task runtimes in Core and consuming products |
| N12-N13 | Miniforge agent context and policy-guidance runtime |
| N14 | Experimental Miniforge deliberation runs, scoped by N14 §0.4 |
| N15 | minibench/workbench evaluation protocol and N14 promotion gates |

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
- **Status vocabulary aligned with N2 §2.2** — §2's Workflow entity still carried the
  superseded `:pending`-based set
- **Conformance requirement IDs** for the domain model (`N1.DM.*`) and architecture
  (`N1.AR.*`), the two families N1's own subject matter lacked, plus test obligations (§8.4–§8.5)
- **Annex A (informative):** which architectural requirements have a static check — interface
  boundaries and stratum direction do; layer direction and status conformance do not

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
- **Canonical workflow status vocabulary (§2.2):**
  `:queued :running :paused :blocked :completed :failed :cancelled`;
  `:pending` and `:executing` withdrawn as synonyms
- **Terminality (§2.2, §8.1):** terminal states never reactivate; re-running is a new run
- **Resume protocol (§8.2–§8.4):** spec-hash comparison, run-identity preservation,
  three staleness conditions replacing "too much time has passed"
- **Conformance requirement IDs** (`N2.LC.*`, `N2.PH.*`, `N2.GT.*`, `N2.RS.*`)
  and test obligations (§10.4–§10.5)
- **Annex A (informative):** implementation conformance status

### N3 — Event Stream & Observability Contract ✅

**File:** [normative/N3-event-stream.md](normative/N3-event-stream.md)
**Status:** Complete
**Purpose:** **Most leverageful spec** - everything UI/analytics/learning builds on this

Defines:

- Event envelope fields and fixed envelope field types (§2.1.1); scope keys —
  workflow, PR Work Item, pack, repo, supervisory entity, deployment (§2.3)
- Required event types (workflow, agent, status, subagent, tool, LLM, messages,
  milestone, gate, pack lifecycle, pack run, chain edge)
- Ordering guarantees (per-scope sequence, causal ordering, replay determinism)
- Streaming API (SSE/WebSocket) with subscription protocol
- Throttling and performance requirements
- Minimal fields needed to render "live" progress and drill-down
- **Reliability metric events:** SLI computation, SLO breach, error budget, degradation mode (§3.17)
- **Repository intelligence events:** Index quality, canary failure (§3.18)
- **Supervisory snapshot family:** twelve `:supervisory/*` types, entity shapes owned by the N5 deltas (§3.19.1)
- **Workflow control events:** cancellation, checkpoint write, machine snapshot, resume (§3.21)
- **Event type registry:** the flat enumeration of every emittable `:event/type`, with scope and retention class (§6)
- **Schema evolution:** what `:event/version` versions, change classification, consumer obligations (§7)
- **Sensitive data & redaction:** never-emitted values, redaction marker, truncation, field classes (§8)
- **Emission failure semantics:** fail-closed for durable/audit classes, sequence integrity (§9)
- **Conformance requirement IDs** (`N3.EV.*`, `N3.EM.*`, `N3.ST.*`, `N3.API.*`, `N3.CP.*`,
  `N3.SD.*`, `N3.EF.*`) and test obligations (§10.4–§10.5)
- **Failure class enum** on all failure events (`:failure/class`, see N1 §5.3.3)
- **Annex A (informative):** implementation conformance status — name divergences, unimplemented and unspecified event types

### N4 — Policy Packs & Gates Standard ✅

**File:** [normative/N4-policy-packs.md](normative/N4-policy-packs.md)
**Status:** Complete
**Purpose:** Make policy-as-code real and pluggable

Defines:

- Four-artifact model: taxonomy (§2.1), pack (§2.2), mapping (§2.4), overlay (§2.5)
- **One severity vocabulary** (`:critical :high :medium :low :info`) shared by rules,
  violations, and every downstream projection (§2.3.1)
- Gate execution contract: check/repair function interfaces with complete protocols
- **Check-function execution semantics:** fail-closed on throw/timeout, resource
  bounds, isolation for untrusted packs, determinism verification (§3.5)
- Semantic intent validation: IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE rules
- Violation schema: severity levels, remediation templates, auto-fix capabilities
- Terraform/Kubernetes-specific validation rules
- **Standard pack registry** with canonical `:pack/id` values and obligation status (§5.1)
- **Pack resolution and precedence:** resolved rule set, conflict rules, version
  conflicts (§5.3)
- **Gate binding:** how a gate acquires rules; an unbound gate fails closed (§5.4)
- **Events and evidence obligations** for every gate execution (§5.5)
- Pack trust, capability grant, and high-risk action gates for Workflow Packs
- **Override and waiver:** what may be overridden, and the durable record (§6.3.1)
- **Signature canonicalization** and trust roots (§8.1.1, §8.2.1)
- **Validation Layer Taxonomy:** L0 Syntax → L1 Semantic → L2 Policy → L3 Operational → L4 Authorization (§3.4)
- **Conformance requirement IDs** (`N4.PK.*`, `N4.EX.*`, `N4.RB.*`, `N4.EN.*`,
  `N4.TR.*`) and test obligations (§9.4–§9.5)
- **Annex A (informative):** implementation conformance status

### N5 — Interface Standard: CLI/TUI/API ✅

**File:** [normative/N5-cli-tui-api.md](normative/N5-cli-tui-api.md)
**Status:** Complete
**Purpose:** User-facing control plane surface area

Defines:

- CLI command taxonomy: eleven namespaces (init, workflow, fleet, policy, evidence,
  artifact, etl, pack, listener, agent, gate)
- TUI primitives: workflow list, detail view, evidence viewer, artifact browser, pack browser, run launcher
- API surface: minimal REST endpoints for workflow control, event streaming, evidence/artifact access;
  streaming wire contract owned by N3 §5.3
- Operations console purpose: monitoring autonomous factory (NOT PR management)
- Manual override mechanisms: plan approval, gate handling, budget escalation
- **Localization contract (§9):** no raw prose at emit sites, user vs system catalogs by
  destination, what is not prose, locale resolution — dewey 050 applied to the console surface
- **CLI output contract (§8.4):** stdout/stderr separation, exit-code taxonomy distinguishing
  policy refusal from failure, `--json` stability, stable error codes
- **Command stability and deprecation (§8.5)**
- **Terminal capability degradation (§8.6):** `NO_COLOR`, no-Unicode, narrow terminals;
  color never the sole carrier of meaning
- **Configuration precedence and validation (§7.3–§7.4):** flag → env → file → default
- **Override bound to the Waiver** of N5-delta-supervisory-control-plane §3.1; `:critical`
  and `:high` not overridable per N4 §6.3.1 (§6.2)
- **Conformance requirement IDs** (`N5.CLI.*`, `N5.TUI.*`, `N5.API.*`, `N5.CFG.*`,
  `N5.L10N.*`, `N5.OV.*`) and test obligations (§8.7–§8.8)
- **Annex A (informative):** implementation conformance status

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
- **Bundle sealing and integrity (§2.14):** canonical-serialization hash, seal-at-creation,
  tamper reporting — the mechanism behind the immutability the spec already asserted
- **Event stream linkage (§2.12):** scope-aware sequence ranges per N3 §2.3
- **Gate execution evidence (§2.13):** binding, exact resolved pack versions, content hashes,
  waivers — discharging the obligations N4 §5.5 places on this spec
- **Retention (§7.4):** `:audit` floor per N3 §4.3.1; bundles outlive neither their events nor artifacts
- **Redaction inherited from N3 §8** rather than a second `[REDACTED:<type>]` marker (§7.2)
- **Conformance requirement IDs** (`N6.EB.*`, `N6.PR.*`, `N6.EL.*`, `N6.GE.*`, `N6.SD.*`,
  `N6.PS.*`) and test obligations (§9.4–§9.5)
- **Annex A (informative):** implementation conformance status
- Compliance metadata: sensitive data handling, audit requirements (SOC 2/FedRAMP)
- **Reliability evidence:** SLI measurements, failure class, workflow tier, degradation mode in outcome (§2.6)
- **Evaluation artifacts:** Golden set and eval-run-result artifact types (§3.1.1)

### Indexed normative amendments

| File | Base contract | Applicability |
|------|---------------|---------------|
| [N2-delta-phase-checkpoint-and-resume.md](normative/N2-delta-phase-checkpoint-and-resume.md) | N2 | Persisted workflow state |
| [N4-delta-policy-compilation-contract.md](normative/N4-delta-policy-compilation-contract.md) | N4 | Policy-pack origination |
| [N5-delta-supervisory-control-plane.md](normative/N5-delta-supervisory-control-plane.md) | N5 | Miniforge supervisory UI/API |
| [N5-delta-2-pr-scoring.md](normative/N5-delta-2-pr-scoring.md) | N5 supervisory | Miniforge PR fleet |
| [N5-delta-3-observational-entities.md](normative/N5-delta-3-observational-entities.md) | N5 supervisory | Miniforge entity projections |
| [N5-delta-4-automation-edge-correlator.md](normative/N5-delta-4-automation-edge-correlator.md) | N5 supervisory | Miniforge automation causality |
| [N11-delta-runtime-adapter.md](normative/N11-delta-runtime-adapter.md) | N11 | Governed task runtimes |

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
- Risk scoring plus requested/effective actuation decisions (RECOMMEND_ONLY, PR_ONLY, APPLY_ALLOWED)

### N8 — Observability Control Interface 🆕

**File:** [normative/N8-observability-control-interface.md](normative/N8-observability-control-interface.md)
**Status:** Draft
**Purpose:** Transform event stream into active control plane for agent fleets

Defines:

- Listener capability model: OBSERVE, ADVISE, CONTROL levels with RBAC
- Control action surface: pause, resume, rollback, quarantine, approve, emergency-stop
- Advisory annotation system: non-blocking recommendations and warnings
- OpenTelemetry interoperability: GenAI span mapping, OTLP export
- Cost and volume controls: sampling rules, aggregation boundaries
- Fleet and enterprise extensions: multi-tenancy, pattern detection
- CLI/TUI extensions: listener commands, control palette, approval queue
- **Safe-mode posture:** Triggers, behavior, exit protocol for system-wide autonomy demotion (§3.4)
- **Redaction and retention deferred to N3** (§5) — the parallel privacy-level,
  pattern-table, field-rule and retention-policy models are withdrawn
- **Per-listener content visibility (§5.1):** N3 §8.4 field classes by RBAC role;
  `:restricted` suppressed per-recipient at delivery
- **Redaction patterns are EDN configuration**, never a function (§5.2, dewey 007)
- **Event schemas referenced, not restated** (§10.1) — the reproduced copies carried a
  fixed `:workflow/id` and were unusable on N3's five non-workflow scopes
- **Conformance requirement IDs** (`N8.CAP.*`, `N8.CTL.*`, `N8.PRV.*`) and test
  obligations (§12.4–§12.5)
- **Annex A (informative):** implementation conformance status

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
- **Scope and event schemas deferred to N3** (§7) — §7.1 restated a PR-only scope rule that
  N3 §2.3 generalizes to six scopes; §7.2 reproduced N3 §3.16's schemas
- **Versioning aligned with N3 §7** (§14) — the required parallel deprecation cycle is
  withdrawn; pre-release implementations cut over
- **Binary name reconciled** — N5 §2.1 documented `miniforge` while the shipped binary is `mf`;
  N9 was correct and N5 is amended
- **Conformance requirement IDs** (`N9.WI.*`, `N9.EV.*`, `N9.AT.*`, `N9.AS.*`, `N9.EB.*`)
  and test obligations (§17–§18)
- **Annex A (informative):** implementation conformance status

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
- **Audit events reframed (§12.1):** none of the fifteen types is registered in N3 §6, so the
  table is informative; adding a row is an N3 amendment first
- **Evidence type gated on N6 (§12.2):** `:governed-execution` is not an N6 §3.1.1 artifact type
- **Annex A (informative):** implementation conformance status — §10's ten safety invariants
  have no enforcement point

---

### N11 — Task Capsule Isolation 🆕

**File:** [normative/N11-task-capsule-isolation.md](normative/N11-task-capsule-isolation.md)
**Status:** Draft
**Purpose:** Make the per-task capsule the primary governed execution boundary

Defines:

- Full enclosure of the agent process, tools, filesystem writes, and emitted artifacts
- Capsule lifecycle: bootstrap, execute, export, destroy
- Local vs governed execution modes with no silent downgrade
- Runtime specification, network, secret, resource, and evidence boundaries
- TaskExecutor workspace persistence and phase continuity
- OCI runtime abstraction through the indexed N11 runtime-adapter delta
- **§11 renumbered** — its five subsections were numbered §10.1–§10.5, colliding with the
  TaskExecutor protocol's own subsections
- **§11 marked informative** — it maps requirements to file/line coordinates that rot; the
  `docker.clj` it cites no longer exists
- **Secrets deferred to N3 §8** (§8.1), keeping only the capsule-specific name/scope allowance
- **§9.1 evidence gated on N6** — its keys are not N6 artifact fields
- **Annex A (informative):** implementation conformance status

---

### N12 — Agent Context Economy 🆕

**File:** [normative/N12-agent-context-economy.md](normative/N12-agent-context-economy.md)
**Status:** Draft
**Purpose:** The context window as a bounded, governed resource — measure it, degrade before bailing, and grow a learned
  symbol language for intent

Defines:

- Foundational principle: compression requires a shared codebook (code is pretrained-shared; novel intent is
  English-bounded)
- Pre-flight measurement: assembled system+user size gauge, ceil token estimate, emitted as an event even on rejection
  (§3)
- Structured overflow detection: `total-input-tokens >= context-window`, never localized error text; terminal +
  non-retryable; effective-model lookup (§4)
- The degradation ladder: assemble → **shed eager context to the query surface** → re-measure → bail last; pre-emptive
  refusal (§5)
- Query surface & symbol handles: deterministic unfold targets, manifests, code's own symbol layer (§6)
- Learned domain language: corpus mining → gated promotion → provenance/versioning → decoder availability; standards
  packs + phase schemas as seed (§7)
- Boundary compressibility classification: phase↔phase (schema-bound today), code→agent, human-intent→planner (§8)

---

### N13 — Policy Injection & Standards Learning 🆕

**File:** [normative/N13-policy-injection-and-standards-learning.md](normative/N13-policy-injection-and-standards-learning.md)
**Status:** Draft
**Purpose:** Split policy into full-fidelity **enforcement** (gates) and compact **guidance** (session injection); learn
  the per-repo guidance subset from violations and promote broadly-valuable rules to a generic bootstrap set

Defines:

- The two tiers: enforcement (complete, detector/gate-checked, no prompt dependence) vs guidance (bounded, relevant,
  injected) — opposite size pressures, conflated today (§1–§2)
- Guidance selection pipeline: static scope → learned rank → changeset relevance → bootstrap fallback → budget cap;
  agent-behavior only, never knowledge-content (§4)
- Compilation obligation: MDC→pack MUST emit `:rule/applies-to` + stable ids; unscoped rule is a warning, not match-all
  (fixes the 153k-char/38k-token full-pack dump into every phase) (§4.6)
- Per-repo violation ledger (local flywheel): rule-attributed signal → persist → rank; zero-violation rules demote (§5)
- Cross-repo promotion/demotion (global flywheel): broadly-violated → generic bootstrap seed; dormant/noisy → demote
  (§6)
- Bootstrap: cold-start from the generic seed; v0 = author's hand-curated list (Appendix A), the existence proof the
  loop automates (§7)
- The gate as safety net: full enforcement de-risks compact guidance — a pruned rule that regresses is caught and
  re-promoted (§9)
- Objective + metrics: minimize injected tokens s.t. gate-pass holds; unattributed findings signal missing detectors
  (§10)

---

### N14 — Shared Deliberation Workspace 🆕

**File:** [normative/N14-shared-deliberation-workspace.md](normative/N14-shared-deliberation-workspace.md)
**Status:** Draft (Speculative — lifecycle bound to N15 gates, see N14 §0.4)
**Purpose:** Typed, event-sourced, transactional shared reasoning state for multi-agent deliberation runs — object
  mutation instead of transcript passing; the substrate for testing whether collective cognition beats matched-compute
  sampling

Defines:

- Closed object taxonomy (goal, constraint, question, claim, hypothesis, experiment, evidence, plan, decision,
  artifact-ref, conflict, blocker) with structural statuses, not numeric confidences (§2)
- Transaction vocabulary with commutative/mergeable/exclusive classes, validation pipeline, and the anti-livelock rule:
  a challenge must carry evidence or a discriminating experiment (§3)
- Deterministic projections as an activation's only input (N12-conformant), plus the `cross_visibility: none` ablation
  switch that makes shared-state value measurable (§4)
- Stateless activations, seven-role v1 population, operation permission matrix (§5)
- Structural v0 scheduler (no model-estimated salience) behind a pluggable seam; budgets (§6)
- Termination: success/budget/quiescence/deadlock closing rules, forced synthesis, dissent-at-close (§7)
- Capsule-bound artifacts by reference; adoption decisions, never merges (§8, N11)
- Workspace events on the N3 envelope; closure exports as N6 artifacts (§9)
- Conformance Stages 0–2 with promotion gated on N15 experiments; demotion to Informative on gate failure (§0.4, §11)

### N15 — Collective-Cognition Evaluation Harness 🆕

**File:** [normative/N15-collective-cognition-harness.md](normative/N15-collective-cognition-harness.md)
**Status:** Draft (core protocol); workspace-conditional sections share N14's speculative status (N15 §0.4)
**Purpose:** Matched-budget evaluation protocol (implemented in minibench) deciding whether multi-agent architectures —
  N14 in particular — beat test-time-compute baselines; pre-registered gates govern N14 staging

Defines:

- Hypothesis registry H1–H5 separating sampling, diversity, aggregation, collective cognition, and reuse (§2)
- Condition set C1–C7: single-pass, best-of-N, independent+synthesis, debate, static pipeline, workspace, and the
  workspace ablation pair (§3)
- Budget protocol: normalized-cost tiers, cost-quality curves not points, overhead fraction as first-class metric (§4)
- Replication (k ≥ 3), comparability preconditions, provenance stamping, absence-as-divergence — the 2026-07-02
  methods-review gaps made normative (§5)
- Long-horizon task class: sealed contradictions, hidden acceptance tests, multi-constraint, decision-record
  deliverable (§6)
- Metrics vector including hypothesis diversity and closed-set failure-category coding (§7)
- Effect rule (between- vs within-spread), ablation delta, pre-registered manifests, Gates G0/G1, regression gating
  (§8)

---

## Informative Documentation (Non-Normative)

These documents provide guidance, examples, and context but do NOT define contractual requirements.

### UX References

- [informative/ux-tui-mockups.md](informative/ux-tui-mockups.md) - Visual design for CLI/TUI (informs N5)
- [informative/ai-ux-flows.md](informative/ai-ux-flows.md) - AI-powered features (informs N3, N5)

### Design and Contract Notes

- [informative/CONFIG-SYSTEM.md](informative/CONFIG-SYSTEM.md) - Configuration precedence and ownership
- [informative/tool-registry.md](informative/tool-registry.md) - Tool registry guidance
- [Workflow supervision architecture](informative/I-WORKFLOW-SUPERVISION-MACHINE-ARCHITECTURE.md) - Design note

> Product strategy documents (pricing, roadmap, competitive positioning) are maintained
> in the private [miniforge-fleet](https://github.com/miniforge-ai/miniforge-fleet) repository.

### Future Workflows

- [informative/pr-monitoring-workflow.md](informative/pr-monitoring-workflow.md) - PR monitoring and conflict resolution

### Architecture & Internals

- [informative/I-ANOMALY-SYSTEM.md](informative/I-ANOMALY-SYSTEM.md) - Canonical error representation and boundary
  translators
- [informative/I-DAG-ORCHESTRATION.md](informative/I-DAG-ORCHESTRATION.md) - DAG executor with PR lifecycle
- [informative/I-DAG-MULTI-PARENT-MERGE.md](informative/I-DAG-MULTI-PARENT-MERGE.md) - v2 of per-task base
  chaining: deterministic octopus merge of multi-parent task bases
- [informative/I-GOVERNANCE-PROVENANCE-GRAPH.md](informative/I-GOVERNANCE-PROVENANCE-GRAPH.md) - Versioned,
  evidence-bearing projection across code, policy, decisions, incidents, claims, and data lineage
- [informative/I-PHASE-HANDOFF-ENVELOPES.md](informative/I-PHASE-HANDOFF-ENVELOPES.md) - Typed phase-transition
  envelopes for durable repair and context handoffs
- [informative/I-TASK-EXECUTOR.md](informative/I-TASK-EXECUTOR.md) - DAG-to-PR lifecycle integration

### Operational Workflows (N10 Extensions)

- [informative/I-VALIDATION-STRATEGIES.md](informative/I-VALIDATION-STRATEGIES.md) -
  Extended validation: formal verification, Shipyard, Tonic, canary execution
- [informative/I-INCIDENT-DIAGNOSTICS.md](informative/I-INCIDENT-DIAGNOSTICS.md) -
  Autonomous incident diagnostics and response workflow patterns

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

**Indexed normative specs:**

- MUST use RFC 2119 keywords: MUST, SHALL, SHOULD, MAY, MUST NOT, SHALL NOT
- MUST define versioning and compatibility expectations
- Breaking changes require version bump

**Informative docs:**

- MUST NOT use RFC 2119 keywords
- Use descriptive language
- Can change without version bump

### Amendment Process

**To add or amend a contract:**

1. Universal concepts MUST land in N1
2. Amendment/extension-local concepts MUST specialize an N1 concept
3. Event and evidence wire contracts MUST be added to N3 or N6
4. UX contracts MUST be added to or reference N3, N5, or N6

**Delta amendments:**

A separate delta MUST name its base spec and scope and appear in this index.
It inherits the base spec's applicability unless this index narrows it.

**Extension specs (N7+):**

Extension specs define capabilities that span multiple core specs (N1-N6).
They MUST:

1. Explicitly define relationship to N1-N6 (what they extend)
2. Reference core specs rather than duplicate contracts
3. Add event types to N3, evidence to N6, commands to N5 as extensions
4. Define a Minimal Compliant Implementation (MCI)

**Rules to prevent spec explosion:**

1. **Core specs (N1-N6)** define universal contracts
2. **Indexed deltas** amend a named contract without duplicating it
3. **Extension specs (N7+)** define scoped product/capability requirements
4. **Wire contracts stay centralized** in N3/N5/N6
5. **Roadmaps never contain contracts** - they link to specs

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

- **0.20.0-draft** (2026-08-10) - Delta-spec completion pass across all seven deltas. Metadata was
  carried three different ways — a core-style header block (N11-delta), a bulleted list under the
  H1 (the four N5 deltas), and a `## Spec metadata` section (N2-delta, N4-delta) — and
  N4-delta had no version anywhere. All normalized to the header form used by N1–N15, with Spec ID,
  Amends and Related preserved. Conformance requirement IDs and test obligations added to the six
  deltas carrying MUSTs: `N2D.CK.*`, `N5D1.SV.*`, `N5D2.SC.*`, `N5D3.OE.*`, `N5D4.AE.*`,
  `N11D.RA.*`. N5-delta-3's second `§3.6` renumbered to `§3.7` — it duplicated the pack-management
  producer's number, and both inbound references mean the producer. **N4-delta contains no RFC 2119
  keyword at all** and so states no requirements; a Status subsection records that it is effectively
  informative until its requirements are stated or it is reclassified, and names the two open
  dispositions rather than choosing one.
- **0.21.0-draft** (2026-08-10) - N12–N15 completion pass. Conformance requirement IDs and test
  obligations added to all four (`N12.CE.*`, `N13.PI.*`, `N14.WS.*`, `N15.CH.*`), plus Annex A on
  each. **N14 §9.1** declared ten `workspace/*` types as required N3 events and none is registered
  in N3 §6, so under N3 §6.1 none may be emitted — the same pattern found in N8, N9, and N10. Since
  §9.1 also makes the event stream the workspace log, the spec's central mechanism is blocked on
  that registration; the list is retained as the proposed content of an N3 amendment rather than
  added to N3's registry, because adding ten unimplemented types would misrepresent the stream's
  surface to every consumer. N14 §9.2's four N6 exports are gated the same way. Annex A notes that
  N15 is the one spec whose absence blocks another's disposition: its §8 gate G0 decides whether
  N14 is kept or demoted, and it cannot run until the harness exists.
  Per-spec bumps: N12 0.1→0.2, N13 0.1→0.2, N14 0.1→0.2, N15 0.1→0.2

- **0.19.0-draft** (2026-08-10) - N11 spec-completion pass. **N11**: §11's five subsections were
  numbered §10.1–§10.5, duplicating the TaskExecutor protocol's subsection numbers; renumbered,
  and the two inbound `N11 §10` references both mean the protocol so are unaffected. §11 marked
  informative — it maps requirements to file and line coordinates that rot, and the `docker.clj`
  it cites no longer exists in the tree. §8.1's secret rules deferred to N3 §8. §9.1's evidence
  keys are not N6 artifact fields and are now gated on registering them there, the same shape as
  N10 §12.2. Annex A records that only three of the runtime classes §5 admits have an executor,
  and that §9.3's prohibition on resolving the workspace from `user.dir` — the exact fallback
  behind the sandbox-leak defect seen in this repo — is unenforced.
  Per-spec bumps: N11 0.2→0.3
- **0.18.0-draft** (2026-08-10) - N1 spec-completion pass. **N1**: §2's Workflow entity declared
  `:workflow/status` with the vocabulary N2 §2.2 superseded — `:pending` rather than `:queued`, and
  no `:paused`/`:blocked`. N1 was a consumer the N2 sweep missed. Added `N1.DM.*` and `N1.AR.*`
  requirement IDs — the domain model and layering are N1's own subject and had no IDs, while six
  families existed for capabilities later amendments added. Annex A separates the architectural
  requirements that have a static check (`poly check` for interfaces, `bb lint:stratum` for stratum
  direction) from those that do not (layer direction, status-vocabulary conformance) — the latter
  being how `:executing` reached the implementation unchallenged. Per-spec bumps: N1 0.7→0.8

- **0.17.0-draft** (2026-08-06) - N10 spec-completion pass. **N10**: §12.1 required governed
  execution to emit events to N3 directly above a note saying implementations MUST NOT emit them,
  since none of the fifteen types is registered in N3 §6 — a requirement satisfiable in neither
  direction. The table is now informative and the conformant path is correlation identifiers on
  registered types, with N3 §6.1 amendment as the route to emitting any of them. §12.2's
  `:governed-execution` evidence shape is not an N6 §3.1.1 artifact type and is gated on
  registering it there. Annex A records that §10's ten safety invariants — including SI-10's
  five-second revocation bound — have no enforcement point, because no capsule, postcondition, or
  crown-jewel component exists. Per-spec bumps: N10 0.3.1→0.4.0
- **0.16.0-draft** (2026-08-06) - N9 spec-completion pass. **N9**: §7.1 restated a PR-only scope
  rule superseded by N3 §2.3's six-scope table, and §7.2 reproduced N3 §3.16's event schemas —
  both now reference N3. §14 required breaking changes to be "supported in parallel for at least
  one deprecation cycle", contradicting N3 §7.4's pre-release cut-over stance; withdrawn. N5 §2.1
  documented the command as `miniforge` while the shipped binary is `mf` (`bb install:cli` →
  `~/.local/bin/mf`, and CI invokes it by that name); N9 was correct and N5 §2.1 is amended, with
  its own examples swept to match.
  Conformance requirement IDs and test obligations (§17–§18). Annex A records that none of N9's
  six event types is emitted, so the `:pr/id` scope has no producer.
  Per-spec bumps: N9 0.2→0.3
- **0.15.0-draft** (2026-08-06) - N8 spec-completion pass. **N8**: §5 carried a parallel model for
  concerns N3 owns — privacy levels, a regex pattern table, a field-rule vocabulary, and its own
  retention schema — so an operator configuring redaction there could not tell whether N3 §8.1's
  MUST NOT still applied. All withdrawn; §5 now defines only which principal sees which field
  class. `:redaction/custom-fn function` withdrawn as a config-as-data violation (dewey 007).
  §10.1 reproduced N3 §3.15's event schemas with a fixed `:workflow/id`, unusable on the five
  non-workflow scopes N3 streams; now a reference table. Conformance requirement IDs and test
  obligations (§12.4–§12.5). Annex A notes that no redaction configuration exists anywhere in the
  tree — the third spec in a row to record that gap. Per-spec bumps: N8 0.3→0.4
- **0.14.0-draft** (2026-08-06) - N2 spec-completion pass. **N2**: the workflow status
  vocabulary was spelled three ways — N2 said `:pending`, N5-delta-supervisory §3.2 said
  `:queued`, and N5 §2.3.2's CLI filter plus the implementation said `:executing`, so a filter
  written against one spec matched nothing produced by another. §2.2 is now canonical and names
  the synonyms withdrawn; `:paused` and `:blocked` added, having been absent from the authority
  while N8 defined a pause action and the supervisory projection reported both. Terminality made
  explicit and §8.1's "user cancelled and wants to restart" resume case withdrawn as contradicting
  it. Resume protocol completed: spec-hash comparison, N3 §3.21 emissions, run-identity
  preservation, and §8.4's three staleness conditions replacing an unenforceable time bound.
  Conformance requirement IDs and test obligations (§10.4–§10.5). Annex A records divergence,
  including that no checkpoint or resume event is emitted anywhere.
  Per-spec bumps: N2 0.5→0.6
- **0.13.0-draft** (2026-08-06) - N6 spec-completion pass. **N6**: bundle sealing and integrity
  (§2.14) — the spec asserted immutability in three places without a mechanism a reader could
  check; event stream linkage schema (§2.12); gate execution evidence (§2.13) discharging the four
  obligations N4 §5.5 places on N6, none of which the bundle recorded; retention (§7.4);
  conformance requirement IDs and test obligations (§9.4–§9.5). Contract fixes: §7.2's
  `[REDACTED:<type>]` marker against N3 §8.2's `[REDACTED]`, and its "redact **or** flag" against
  N3 §8.1's MUST NOT; §2.1 and §7.1 compliance keys disagreeing in both directions; §8.1–§8.2
  restating N5's CLI/TUI contracts. Annex A records implementation divergence — notably that the
  scanner detects secrets but never redacts them. Per-spec bumps: N6 0.7.2→0.8.0
- **0.12.0-draft** (2026-08-05) - N5 spec-completion pass. **N5**: localization contract (§9)
  applying dewey 050 to the console surface — the spec defining the largest prose surface in the
  system had none; CLI output contract with stdout/stderr separation, an exit-code taxonomy that
  distinguishes policy refusal from failure, `--json` stability, and stable error codes (§8.4);
  command stability and deprecation (§8.5); terminal capability degradation (§8.6); configuration
  precedence and validation (§7.3–§7.4); conformance requirement IDs and test obligations
  (§8.7–§8.8). Contract fixes: §5.2's "not a chat interface" against §3.2.8/§3.2.9 mandating a
  chat key; `c` collided between Cancel and chat; §2.2's namespace table missing three namespaces
  §2.3.3 defined commands for; §6.1.2 offering override for a CRITICAL violation that N4 §6.3.1
  forbids; §6.2's bespoke override record replaced by the Waiver; §4.2.2/§4.3 aligned with N3 §5.3;
  §3.2.8–§3.2.9 stopped mandating implementation namespaces per standard 020.
  Annex A records implementation divergence. Per-spec bumps: N5 0.4→0.5
- **0.11.0-draft** (2026-08-05) - N4 spec-completion pass. **N4**: unified the severity vocabulary
  (§2.3.1 had `:error`/`:warning`/`:info` against the canonical `:critical :high :medium :low :info`
  used everywhere else in the same spec); check-function execution semantics with fail-closed
  behaviour, resource bounds, and isolation (§3.5); taxonomy compatibility (§2.1.1); standard pack
  registry and identifier convention (§5.1); pack resolution and precedence (§5.3); gate binding
  (§5.4); events and evidence obligations (§5.5); override/waiver contract bound to
  N5-delta-supervisory-control-plane §3.1 (§6.3.1); signature canonicalization and trust roots (§8.1.1, §8.2.1);
  conformance requirement IDs and test obligations (§9.4–§9.5). Contract fixes: `:violation/rule-id`
  typed keyword, `:violation/pack-id` added, §11.1 example rewritten off the pre-0.6
  `:policy-pack/*` namespace, duplicate `require-capability-declaration` rule ID split.
  Annex A records implementation divergence. Per-spec bumps: N4 0.6→0.7
- **0.10.0-draft** (2026-08-05) - N3 spec-completion pass. **N3**: canonical event type registry (§6),
  schema evolution and consumer compatibility rules (§7), sensitive-data and redaction contract (§8),
  emission-failure semantics with fail-closed durable/audit classes (§9), conformance requirement IDs
  and test obligations (§10.4–§10.5), workflow control and checkpoint event family (§3.21) sourced from
  N2 §5 and N2-delta §9, `listener/overflow` defined (§3.15), supervisory family enumerated at twelve
  members (§3.19.1), retention classes (§4.3.1–§4.3.3), scope-key table generalized beyond PR-only
  (§2.3). Contract fixes: `:pr/id` unified as PR Work Item UUID with `:pr/number` for provider numbers,
  bare `:timestamp` removed, `:event/sequence-number` unified on `long`, duplicate §3.17 resolved
  (Data Foundry → §3.20). Annex A records implementation divergence as tracked work.
  Per-spec bumps: N3 0.9→0.10
- **0.9.0-draft** (2026-08-04) - Indexed every normative amendment and extension with explicit
  product applicability; reconciled N7 requested/effective actuation, OPSV event/evidence
  correlation, and N8/N10 governance semantics
- **0.8.0-draft** (2026-07-23) - Added N14 (Shared Deliberation Workspace) and N15 (Collective-Cognition
  Evaluation Harness). N14 is a speculative spec: conformance binds experimental implementations pre-gate and
  the spec demotes to Informative as a recorded negative result if N15 Gate G0 fails. N15's core protocol
  (budget matching, replication, comparability, task class, metrics) is architecture-agnostic and survives any
  gate outcome; its workspace-conditional sections (C6/C7, ablation delta, G0/G1) share N14's lifecycle
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
