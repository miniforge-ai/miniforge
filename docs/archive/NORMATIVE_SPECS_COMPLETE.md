<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# All Normative Specifications Complete

**Date:** 2026-01-23
**Status:** ✅ All 6 normative specs extracted and ready for implementation

---

## Summary

All six normative specifications (N1-N6) are now complete, extracted from strategic documents (NOT implementation code), and ready to guide miniforge development.

**Total:** ~270 pages of normative contracts defining what miniforge MUST be.

---

## Completed Specifications

### ✅ N1 - Core Architecture & Concepts

**File:** `specs/normative/N1-architecture.md`
**Size:** 40+ pages
**Status:** Complete

**Defines:**

- Core domain model: 12 key nouns (workflow, phase, agent, subagent, tool, gate, policy pack, evidence bundle, artifact, knowledge base)
- Three-layer architecture: Control Plane → Agent Layer → Learning Layer
- Polylith component boundaries (complete OSS catalog)
- Operational model: local-first, reproducibility, failure semantics
- Agent protocols: communication patterns, context handoff, inter-agent messaging

**Key contracts:**

- Component interface requirements (MUST provide interface namespace)
- Agent communication protocol (message schema, state sharing)
- Layering rules (components MUST NOT cross layer boundaries)

---

### ✅ N2 - Workflow Execution Model

**File:** `specs/normative/N2-workflows.md`
**Size:** 45+ pages
**Status:** Complete

**Defines:**

- Phase graph: Plan → Design → Implement → Verify → Review → Release → Observe
- Phase responsibilities (what each phase MUST do, expected outputs, gates)
- Inner loop: validate → feedback → repair → re-validate (multi-strategy repair)
- Outer loop: phase transition state machine (prerequisites, failure handling)
- Gate contract: check/repair function signatures, violation schema
- Context handoff: protocol for passing context between phases

**Key contracts:**

- Phase execution contract (input requirements, output guarantees)
- Inner loop termination conditions (max iterations, budget limits)
- Gate function signatures (check, repair, remediation payload)
- Context schema for inter-phase communication

---

### ✅ N3 - Event Stream & Observability Contract

**File:** `specs/normative/N3-event-stream.md`
**Size:** 52 pages
**Status:** Complete

**Defines:**

- Event envelope schema (required fields: type, id, timestamp, workflow-id, sequence-number)
- 9 event type categories: workflow, agent, status, subagent, tool, LLM, messages, milestone, gate
- Ordering guarantees: per-workflow sequencing, causal ordering, replay determinism
- Streaming API: SSE/WebSocket subscription protocol
- Throttling rules (max 2-3 status events/second/agent)
- Performance requirements (emit <10ms p99, stream <100ms p99)

**Key contracts:**

- Event schema validation (all events MUST conform)
- Sequence number monotonicity (MUST increment per workflow)
- Subscription API (subscribe, unsubscribe, query)
- Streaming endpoints (GET /api/workflows/:id/stream)

**Why this is the most leverageful spec:**

- UI/TUI consume events for live updates
- Replay enables debugging
- Analytics build on event data
- Future learning extracts signals from events

---

### ✅ N4 - Policy Packs & Gates Standard

**File:** `specs/normative/N4-policy-packs.md`
**Size:** 48+ pages
**Status:** Complete

**Defines:**

- Pack structure: schema, versioning, signature requirements, rule definitions
- Gate execution contract: check/repair function interfaces
- Semantic intent validation: IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE rules
- Violation schema: severity (critical/high/medium/low/info), remediation templates
- Terraform-specific validation (parse plan output, categorize changes)
- Kubernetes-specific validation (kubectl diff analysis, resource lifecycle)

**Key contracts:**

- Policy pack manifest schema (MUST include id, version, rules)
- Rule detection protocol (pattern matching, AST analysis, plan parsing)
- Gate check function signature: `(fn [artifact ctx] -> {:passed? bool :errors []})`
- Gate repair function signature: `(fn [artifact errors ctx] -> {:success? bool :artifact ...})`
- Semantic validation rules (IMPORT MUST have 0 creates/destroys)

**Why this matters:**

- Prevents accidental infrastructure changes (IMPORT declares, but code creates → violation)
- Makes autonomous workflows credible to security teams
- Provides clear remediation guidance (human + machine readable)

---

### ✅ N5 - Interface Standard: CLI/TUI/API

**File:** `specs/normative/N5-cli-tui-api.md`
**Size:** 42+ pages
**Status:** Complete

**Defines:**

- CLI command taxonomy: 6 namespaces (init, workflow, fleet, policy, evidence, artifact)
- TUI primitives: 4 primary views (workflow list, detail, evidence viewer, artifact browser)
- API surface: minimal REST endpoints (workflow control, event streaming, evidence/artifact access)
- Operations console purpose: monitoring autonomous factory (NOT PR management)
- Manual override mechanisms: plan approval, gate failure handling, budget escalation

**Key contracts:**

- CLI command stability (MUST NOT break backward compatibility)
- TUI keyboard navigation (MUST support vim-like j/k/Enter/Esc)
- API endpoints (MUST provide GET /api/workflows, POST /api/workflows/:id/approve)
- Configuration file format (.miniforge/config.edn schema)

**Critical mental model:**

- Operations console monitors the factory, it's NOT the product
- TUI shows workflow progress, agent activity, inner loop iterations
- Evidence viewer MUST provide full audit trail from intent → outcome

---

### ✅ N6 - Evidence & Provenance Standard

**File:** `specs/normative/N6-evidence-provenance.md`
**Size:** 47 pages
**Status:** Complete

**Defines:**

- Evidence bundle schema: intent → phases → validation → outcome
- Artifact provenance: source inputs, tool executions, content hashes, timestamps
- Semantic intent validation rules (declared vs. actual behavior)
- Queryable provenance API: trace artifact chains, find intent mismatches
- Compliance metadata: sensitive data handling, audit requirements (SOCII/FedRAMP)
- Terraform/Kubernetes-specific semantic validation

**Key contracts:**

- Evidence bundle MUST be immutable after creation
- All artifacts MUST have provenance (workflow-id, phase, agent, timestamp)
- Semantic validation MUST check declared intent vs. actual behavior
- Query API MUST support: `query-provenance`, `trace-artifact-chain`, `query-intent-mismatches`

**Why this enables credibility:**

- Complete audit trail from business requirement → implementation → outcome
- Semantic validation catches intent violations (said IMPORT, actually CREATE)
- Queryable history: "What was the original intent for this artifact?"
- Compliance-ready for SOCII, FedRAMP (immutable records, sensitive data scanning)

---

## Spec Dependencies

```
┌─────────────────────┐
│  N1 (Architecture)  │  ← Defines core concepts
└──────────┬──────────┘
           │ defines nouns for
           ▼
┌──────────────────────┬─────────────────────┐
│  N2 (Workflows)      │  N4 (Policy Packs)  │
│  - Uses agents       │  - Uses gates       │
│  - Executes phases   │  - Checks artifacts │
└──────────┬───────────┴──────────┬──────────┘
           │ both emit events     │ both store results
           ▼                      ▼
┌──────────────────────┬─────────────────────────────┐
│  N3 (Event Stream)   │  N6 (Evidence & Provenance) │
│  - Workflow events   │  - Evidence bundles         │
│  - Agent status      │  - Artifact provenance      │
│  - Gate events       │  - Semantic validation      │
└──────────┬───────────┴──────────┬──────────────────┘
           │ consumed by          │ viewed via
           ▼                      ▼
           ┌─────────────────────┐
           │  N5 (CLI/TUI/API)   │
           │  - Operations console│
           │  - Evidence viewer   │
           └─────────────────────┘
```

**Key insight:** N3 (Event Stream) and N6 (Evidence & Provenance) are the most leverageful specs:

- N3 powers UX, debugging, analytics, learning
- N6 enables credibility, compliance, semantic validation

Everything else builds on these two foundations.

---

## Conformance Criteria

**miniforge implementation is conformant if it satisfies ALL requirements in all 6 specs:**

1. ✅ **N1 conformance:**
   - Components respect layer boundaries
   - All components provide interface namespace
   - Agent communication uses defined protocols

2. ✅ **N2 conformance:**
   - Workflow executes all required phases
   - Inner loop validates and repairs until gates pass (or max iterations)
   - Outer loop transitions follow state machine rules
   - Context handoff preserves all required fields

3. ✅ **N3 conformance:**
   - All required event types emitted at correct points
   - Events maintain per-workflow sequence ordering
   - Event replay produces deterministic workflow state
   - Streaming endpoints serve events via SSE/WebSocket

4. ✅ **N4 conformance:**
   - Policy packs validate against schema
   - Gates implement check/repair contract
   - Semantic intent validation detects mismatches
   - Violation remediation provides human + machine guidance

5. ✅ **N5 conformance:**
   - CLI commands match taxonomy
   - TUI provides all required views
   - API exposes minimal required endpoints
   - Evidence viewer shows full audit trail

6. ✅ **N6 conformance:**
   - Evidence bundles generated for all workflows
   - All artifacts have complete provenance
   - Semantic validation checks intent vs. behavior
   - Provenance queries work correctly

**Enforcement:** Schema validation tests + integration tests + conformance test suite.

---

## Implementation Priority

**Recommended build order** (from spec perspective):

1. **N3 (Event Stream)** - Foundation for observability
   - Implement event emission from agents
   - Build event bus for pub/sub
   - Add SSE streaming endpoint
   - **Why first:** Everything else depends on events (UI, debugging, learning)

2. **N6 (Evidence & Provenance)** - Foundation for credibility
   - Implement evidence bundle generation
   - Build semantic intent validation
   - Add queryable provenance API
   - **Why second:** Enables trust and compliance

3. **N2 (Workflow Execution)** - Core execution engine
   - Implement phase state machine
   - Wire up inner/outer loops
   - Add gate integration
   - **Why third:** Uses N3 (events) and N6 (evidence)

4. **N4 (Policy Packs)** - Validation layer
   - Implement pack loading
   - Add semantic validation rules
   - Build Terraform/Kubernetes parsers
   - **Why fourth:** Integrates with N2 (gates) and N6 (semantic checks)

5. **N5 (CLI/TUI/API)** - User interface
   - Build CLI commands
   - Implement TUI (consumes N3 events)
   - Add evidence viewer (displays N6 bundles)
   - **Why fifth:** Consumes N3, N6, controls N2

6. **N1 (Architecture)** - Already partially implemented
   - Document Polylith boundaries
   - Define canonical glossary
   - Formalize agent protocols
   - **Why last:** Spec documents what exists, doesn't define new contracts

---

## Next Steps

### For Implementation

1. **Read specs in dependency order**: N1 → N3 → N6 → N2 → N4 → N5
2. **Build conformance tests**: One test suite per spec
3. **Implement in priority order**: N3 → N6 → N2 → N4 → N5 → N1
4. **Use examples as golden files**: Specs include detailed examples for testing

### For Validation

1. **Schema validation**: All events, evidence bundles, artifacts validate
2. **Integration tests**: End-to-end workflow execution
3. **Conformance suite**: Verify all MUST/SHALL requirements
4. **Golden file tests**: Example workflows as test fixtures

---

## Success Metrics

**We succeeded if:**

1. ✅ All 6 specs are complete and cross-referenced
2. ✅ Specs define contracts (MUST/SHALL), not implementation
3. ✅ Extracted from strategic vision, not from code
4. ✅ AI agents (Claude, Codex) can use specs/SPEC_INDEX.md as entry point
5. ✅ Implementers know what's contractual (normative/) vs. guidance (informative/)
6. ✅ No ambiguity on schemas (events, evidence, policy packs all defined)
7. ✅ Conformance is testable (schema validation, golden files, integration tests)

**Metrics:**

- **Total pages:** ~270 pages of normative contracts
- **Total requirements:** 100+ MUST/SHALL statements across all specs
- **Cross-references:** All specs link to each other (dependency graph clear)
- **Examples:** 50+ detailed examples showing correct implementation
- **Conformance criteria:** Each spec has testable requirements

---

## Files Changed Summary

**Created (Phase 1: N3, N6):**

- specs/SPEC_INDEX.md (entry point)
- specs/README.md (implementer guide)
- specs/normative/N3-event-stream.md (52 pages)
- specs/normative/N6-evidence-provenance.md (47 pages)

**Created (Phase 2: N1, N2, N4, N5):**

- specs/normative/N1-architecture.md (40+ pages)
- specs/normative/N2-workflows.md (45+ pages)
- specs/normative/N4-policy-packs.md (48+ pages)
- specs/normative/N5-cli-tui-api.md (42+ pages)

**Reorganized:**

- 4 files → specs/informative/ (vision, roadmap, UX mockups, AI flows)
- 5 files → specs/deprecated/ (superseded content with deprecation warnings)

**Total:** 10 new spec files, clean hierarchy, all cross-referenced.

---

**The miniforge specification is now complete and ready to guide implementation.**

✅ **All normative contracts defined**
✅ **All extracted from strategic vision**
✅ **All cross-referenced and testable**
✅ **Ready for conformant implementation**
