# miniforge Specification Index

**Version:** 0.1.0-draft
**Date:** 2026-01-23
**Status:** Living specification during OSS development

---

## What miniforge Is (Canonical Description)

**miniforge** executes a **workflow DAG** (planner → implementer → tester → reviewer → release manager) with an **inner validate/repair loop** and **explicit gates** (lint/coverage/stratification/docs/policy/etc). It produces **evidence bundles** and **artifacts with provenance**, while emitting an **append-only event stream** (agent status, tool use, subagents, LLM calls, messages) so the CLI/TUI can show live progress and drill-down without scraping logs.

**The interesting parts for experts:**

* **Event stream as product surface area** (not just logging): it powers UX, replay/debuggability, and later learning/analytics.
* **Evidence bundles + semantic intent validation** as the primitive that makes "autonomous" credible to platform/security teams.
* **High-throughput triage UI** optimized for 100+ PR/day (email-triage model + batch operations).

---

## Normative Specifications (MUST/SHALL)

These six specifications define contractual requirements for miniforge implementations. They use RFC 2119 terminology (MUST, SHALL, SHOULD, MAY).

### N1 — Core Architecture & Concepts ✅

**File:** [normative/N1-architecture.md](normative/N1-architecture.md)
**Status:** Complete
**Purpose:** Stable conceptual model and layering boundaries

Defines:

* Core nouns: workflow, phase, agent, subagent, tool, gate, policy pack, evidence bundle, artifact, provenance
* Three-layer architecture: Control Plane, Agent Layer, Learning Layer
* Polylith component boundaries (OSS component catalog)
* Operational model: local-first execution, reproducibility, failure semantics
* Agent protocols: communication patterns, context handoff, inter-agent messaging

### N2 — Workflow Execution Model ✅

**File:** [normative/N2-workflows.md](normative/N2-workflows.md)
**Status:** Complete
**Purpose:** Engine contract for work representation and execution

Defines:

* Phase graph: Plan → Design → Implement → Verify → Review → Release → Observe
* Phase responsibilities: detailed requirements for each phase (inputs, outputs, gates)
* Inner loop: validate → feedback → repair → re-validate with multi-strategy repair
* Outer loop: phase transition state machine with prerequisites and failure handling
* Gate contract: check/repair function signatures, violation schema, enforcement rules
* Context handoff: protocol for passing context between phases

### N3 — Event Stream & Observability Contract ✅

**File:** [normative/N3-event-stream.md](normative/N3-event-stream.md)
**Status:** Complete
**Purpose:** **Most leverageful spec** - everything UI/analytics/learning builds on this

Defines:

* Event envelope fields; required event types (workflow, agent, status, subagent, tool, LLM, messages, milestone, gate)
* Ordering guarantees (per-workflow sequence, causal ordering, replay determinism)
* Streaming API (SSE/WebSocket) with subscription protocol
* Throttling and performance requirements
* Minimal fields needed to render "live" progress and drill-down

### N4 — Policy Packs & Gates Standard ✅

**File:** [normative/N4-policy-packs.md](normative/N4-policy-packs.md)
**Status:** Complete
**Purpose:** Make policy-as-code real and pluggable

Defines:

* Pack structure: schema, versioning, signature requirements, rule definitions
* Gate execution contract: check/repair function interfaces with complete protocols
* Semantic intent validation: IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE rules
* Violation schema: severity levels, remediation templates, auto-fix capabilities
* Terraform/Kubernetes-specific validation rules

### N5 — Interface Standard: CLI/TUI/API ✅

**File:** [normative/N5-cli-tui-api.md](normative/N5-cli-tui-api.md)
**Status:** Complete
**Purpose:** User-facing control plane surface area

Defines:

* CLI command taxonomy: six namespaces (init, workflow, fleet, policy, evidence, artifact)
* TUI primitives: workflow list, detail view, evidence viewer, artifact browser
* API surface: minimal REST endpoints for workflow control, event streaming, evidence/artifact access
* Operations console purpose: monitoring autonomous factory (NOT PR management)
* Manual override mechanisms: plan approval, gate handling, budget escalation

### N6 — Evidence & Provenance Standard ✅

**File:** [normative/N6-evidence-provenance.md](normative/N6-evidence-provenance.md)
**Status:** Complete
**Purpose:** Credibility - prove what happened and why it is safe

Defines:

* Evidence bundle schema: intent → phases → validation → outcome
* Artifact provenance: source inputs, tool executions, content hashes, timestamps
* Semantic intent validation rules with Terraform/Kubernetes specifics
* Queryable provenance API: trace artifact chains, find intent mismatches
* Compliance metadata: sensitive data handling, audit requirements (SOCII/FedRAMP)

---

## Informative Documentation (Non-Normative)

These documents provide guidance, examples, and context but do NOT define contractual requirements.

### UX References

* [informative/ux-tui-mockups.md](informative/ux-tui-mockups.md) - Visual design for CLI/TUI (informs N5)
* [informative/ai-ux-flows.md](informative/ai-ux-flows.md) - AI-powered features (informs N3, N5)

### Guides (How-To)

* [informative/getting-started.md](informative/getting-started.md) - First workflow guide
* [informative/authoring-policies.md](informative/authoring-policies.md) - Policy pack development
* [informative/writing-workflows.md](informative/writing-workflows.md) - Workflow spec authoring
* [informative/building-scanners.md](informative/building-scanners.md) - Scanner development

### Vision & Positioning

* [informative/software-factory-vision.md](informative/software-factory-vision.md) - Product vision and differentiation
* [informative/operational-modes.md](informative/operational-modes.md) - OSS vs Paid operational differences

### Roadmaps (Experimental/Future)

* [informative/learning-meta-loop.md](informative/learning-meta-loop.md) - Future learning system (post-OSS)
* [informative/oss-paid-roadmap.md](informative/oss-paid-roadmap.md) - Product roadmap and go-to-market

---

## Examples (Reference Implementations)

Concrete examples that demonstrate compliance with normative specs.

### Workflow Examples

* [examples/workflows/rds-import.edn](examples/workflows/rds-import.edn) - Import existing RDS to Terraform
* [examples/workflows/k8s-deployment.edn](examples/workflows/k8s-deployment.edn) - Deploy to Kubernetes
* [examples/workflows/vpc-network-changes.edn](examples/workflows/vpc-network-changes.edn) - Network infrastructure

### Evidence Bundle Examples

* [examples/evidence/rds-import-bundle.edn](examples/evidence/rds-import-bundle.edn) - Complete evidence bundle
* [examples/evidence/semantic-validation.edn](examples/evidence/semantic-validation.edn) - Intent validation example

### Policy Pack Examples

* [examples/policy-packs/terraform-aws/](examples/policy-packs/terraform-aws/) - Terraform AWS safety checks
* [examples/policy-packs/kubernetes/](examples/policy-packs/kubernetes/) - K8s manifest validation
* [examples/policy-packs/foundations/](examples/policy-packs/foundations/) - General code quality

---

## Deprecated Documents

Documents superseded by normative specs. Retained for reference during migration.

* [deprecated/BUILD_PLAN.md](deprecated/BUILD_PLAN.md) - Superseded by informative roadmap
* [deprecated/BUILD_PLAN_REVISED.md](deprecated/BUILD_PLAN_REVISED.md) - Content extracted to N2, N3, N6
* [deprecated/OSS_PAID_ROADMAP.md](deprecated/OSS_PAID_ROADMAP.md) - Superseded by informative/oss-paid-roadmap.md
* [deprecated/REVISED_TIMELINE.md](deprecated/REVISED_TIMELINE.md) - Merged into roadmap
* [deprecated/AGENT_STATUS_STREAMING.md](deprecated/AGENT_STATUS_STREAMING.md) - Content extracted to N3

---

## Specification Governance

### Language Rules

**Normative specs (N1-N6):**

* MUST use RFC 2119 keywords: MUST, SHALL, SHOULD, MAY, MUST NOT, SHALL NOT
* MUST define versioning and compatibility expectations
* Breaking changes require version bump

**Informative docs:**

* MUST NOT use RFC 2119 keywords
* Use descriptive language
* Can change without version bump

### Amendment Process

**To add a new concept:**

1. It MUST land in N1 (glossary + concept model) first
2. Runtime data MUST land in N3 (events) or N6 (evidence/artifacts)
3. UX features MUST point to a contract in N3/N5/N6

**Rules to prevent spec explosion:**

1. **No new normative spec files** - only amend N1-N6
2. **Every new concept must land in N1** or it's not real
3. **Every new runtime datum must land in N3 or N6** or it's unobservable
4. **Every UX feature must point to a contract** or it's just a mock
5. **Roadmaps never contain contracts** - they link to specs

### Conformance

Normative specs are enforced by:

* Schema validation tests (events/evidence/policy packs)
* Golden-file examples
* CLI contract tests
* Gate validation (specs enforced by gates)

---

## Implementation Notes

**Current focus:** N3 (Event Stream) and N6 (Evidence & Provenance) are foundational.

**Why start with N3 & N6?**

* Event stream powers UX, replay, debugging, and future learning
* Evidence bundles enable credibility and compliance
* These two specs force clarity across workflow engine, TUI, policy gates, and learning

**Implementation priority:**

1. N3 - Event stream protocol and emission from agents
2. N6 - Evidence bundle schema and provenance tracking
3. N5 - CLI/TUI interface consuming N3 events
4. N2 - Workflow execution model (already mostly implemented)
5. N4 - Policy pack standard (already mostly implemented)
6. N1 - Architecture (extracted from implementation)

---

## Version History

* **0.1.0-draft** (2026-01-23) - Initial spec index, normative spec structure established
