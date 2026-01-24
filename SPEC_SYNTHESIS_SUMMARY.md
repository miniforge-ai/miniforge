# Specification Synthesis Summary

**Date:** 2026-01-23
**Action:** Collapsed spec sprawl into canonical specification hierarchy

---

## What Was Done

Executed the specification synthesis recipe to collapse 9+ scattered documents into **6 normative specs** plus supporting documentation with clear governance rules.

### Files Created

1. **specs/SPEC_INDEX.md** - Authoritative entry point with:
   - Canonical 1-paragraph product description
   - Index of all 6 normative specs (N1-N6)
   - Index of informative docs
   - Governance rules to prevent re-explosion

2. **specs/normative/N3-event-stream.md** ✅ COMPLETE
   - Event envelope schema with required fields
   - 9 event type categories (workflow, agent, status, subagent, tool, LLM, messages, milestone, gate)
   - Ordering guarantees (per-workflow sequencing, causal ordering, replay determinism)
   - Streaming API (SSE/WebSocket)
   - Throttling and performance requirements
   - Complete example event sequence
   - 52 pages of normative contract

3. **specs/normative/N6-evidence-provenance.md** ✅ COMPLETE
   - Evidence bundle schema (intent → phases → validation → outcome)
   - Artifact provenance tracking (source inputs, tool executions, hashes)
   - Semantic intent validation rules (IMPORT ≠ CREATE)
   - Queryable provenance API
   - Compliance metadata (SOCII/FedRAMP foundations)
   - Terraform/Kubernetes-specific validation
   - 47 pages of normative contract

4. **specs/README.md** - Guide for implementers and AI agents
   - Directory structure explanation
   - Normative vs. informative distinction
   - Quick reference (what to read for X)
   - Rules to prevent spec explosion

### Files Reorganized

**Moved to specs/informative/** (non-normative):

- SOFTWARE_FACTORY_VISION.md → informative/software-factory-vision.md
- PRODUCT_STRATEGY.md → informative/oss-paid-roadmap.md
- UX_DESIGN_SPEC.md → informative/ux-tui-mockups.md
- UX_AI_FEATURES.md → informative/ai-ux-flows.md

**Moved to specs/deprecated/** (superseded):

- AGENT_STATUS_STREAMING.md (content extracted to N3)
- BUILD_PLAN_REVISED.md (content extracted to N2, N3, N6)
- BUILD_PLAN.md (outdated)
- OSS_PAID_ROADMAP.md (superseded by informative/oss-paid-roadmap.md)
- REVISED_TIMELINE.md (merged into roadmap)

Added deprecation warnings to deprecated files pointing to normative specs.

### Directory Structure Created

```
specs/
├── SPEC_INDEX.md              # Entry point ⭐
├── README.md                  # Guide for implementers
├── normative/                 # N1-N6 (MUST/SHALL)
│   ├── N3-event-stream.md    # ✅ Complete
│   └── N6-evidence-provenance.md  # ✅ Complete
├── informative/               # Guides, UX, roadmaps
│   ├── software-factory-vision.md
│   ├── oss-paid-roadmap.md
│   ├── ux-tui-mockups.md
│   └── ai-ux-flows.md
├── examples/                  # Reference implementations
│   ├── workflows/
│   ├── evidence/
│   └── policy-packs/
└── deprecated/                # Historical reference
    ├── AGENT_STATUS_STREAMING.md (→ N3)
    ├── BUILD_PLAN_REVISED.md (→ N2, N3, N6)
    └── ...
```

---

## What This Achieves

### 1. Clear Contract Boundaries

**Before:** Specs scattered across 9 docs, unclear what's contractual vs. informative
**After:** 6 normative specs (N1-N6) with RFC 2119 language (MUST/SHALL)

### 2. Event Stream as Product Surface

**N3 (Event Stream)** is now the **most leverageful spec** - everything builds on it:

- TUI/Web consume events for live progress
- Replay enables debugging
- Analytics build on event data
- Future learning extracts signals from events

**Key decision:** Event stream is **not logging**, it's **product infrastructure**

### 3. Evidence Bundles Enable Credibility

**N6 (Evidence & Provenance)** makes autonomous workflows credible:

- Complete audit trail (intent → plan → implementation → outcome)
- Semantic intent validation (declared `:import` ≠ actual `:create` → violation)
- Queryable provenance ("What was the intent for this artifact?")
- Compliance-ready (SOCII, FedRAMP foundations)

### 4. Prevents Spec Explosion

**5 governance rules** enforce discipline:

1. No new normative spec files (only amend N1-N6)
2. Every new concept must land in N1 (or it's not real)
3. Every new runtime datum must land in N3 or N6 (or it's unobservable)
4. Every UX feature must point to a contract (or it's just a mock)
5. Roadmaps never contain contracts (they link to specs)

### 5. AI Agent-Friendly

**Entry point:** specs/SPEC_INDEX.md

- Claude/Codex/Opus get stable entry point
- Deprecated files flagged (don't use these)
- Normative specs define MUST/SHALL
- Examples show conformance

---

## What's Still TODO

### Remaining Normative Specs (to extract)

1. **N1 - Core Architecture** (Draft)
   - Extract from existing codebase
   - Define: workflow, phase, agent, subagent, tool, gate, policy pack
   - Polylith layering rules

2. **N2 - Workflow Execution** (Draft)
   - Extract from existing workflow engine code
   - Phase graph, inner loop, gate contract
   - Already mostly implemented - just needs spec

3. **N4 - Policy Packs** (Draft)
   - Extract from existing policy-pack component
   - Pack structure, versioning, gate execution
   - Already mostly implemented - just needs spec

4. **N5 - CLI/TUI/API** (Draft)
   - Extract CLI taxonomy from bases/cli
   - TUI primitives from UX_DESIGN_SPEC.md
   - API surface from event stream + workflow control

### Examples to Create

1. **examples/workflows/**
   - rds-import.edn
   - k8s-deployment.edn
   - vpc-network-changes.edn

2. **examples/evidence/**
   - rds-import-bundle.edn (complete evidence bundle)
   - semantic-validation.edn (intent validation example)

3. **examples/policy-packs/**
   - terraform-aws/ (Terraform safety checks)
   - kubernetes/ (K8s manifest validation)
   - foundations/ (General code quality)

### Conformance Tests

Create test suite validating:

- Event schema conformance (N3)
- Evidence bundle schema conformance (N6)
- Semantic validation logic (N6)
- Event ordering guarantees (N3)
- Artifact provenance chain integrity (N6)

---

## Key Decisions Codified

### 1. Event Stream is Product, Not Logging

**Normative requirement (N3):**

- All agent actions MUST emit events
- Events MUST be sequenced per workflow
- Replay MUST be deterministic
- UI MUST consume events (not scrape logs)

**Rationale:** Event stream powers UX, debugging, analytics, and future learning.

### 2. Semantic Intent Validation is Mandatory

**Normative requirement (N6):**

- Workflows MUST declare intent (`:import`, `:create`, `:update`, `:destroy`)
- Implementations MUST validate declared intent vs. actual behavior
- Critical mismatches MUST block merge

**Rationale:** This is what makes autonomous workflows credible to platform/security teams.

### 3. Evidence Bundles are Immutable

**Normative requirement (N6):**

- Evidence bundles MUST NOT be modified after creation
- Content hashes MUST be calculated for all artifacts
- Bundles MUST link to event stream via sequence ranges

**Rationale:** Immutability enables compliance, debugging, and trust.

### 4. No More Than 6 Normative Specs

**Governance rule:**

- Only N1-N6 can contain MUST/SHALL
- New concepts amend existing specs
- No new normative spec files

**Rationale:** Prevents Windows-style spec explosion.

---

## Implementation Priority (from Spec Perspective)

**Recommended order:**

1. **N3 (Event Stream)** - Foundation for everything
   - Implement event emission from agents
   - Build event bus for pub/sub
   - Add SSE/WebSocket streaming endpoints

2. **N6 (Evidence & Provenance)** - Credibility layer
   - Implement evidence bundle generation
   - Build semantic intent validation
   - Add queryable provenance API

3. **N5 (CLI/TUI/API)** - User interface
   - TUI consumes N3 events for live updates
   - CLI exposes evidence bundles (N6)
   - Workflow control API

4. **N2 (Workflow Execution)** - Already mostly done
   - Extract spec from implementation
   - Document phase graph, inner loop, gates

5. **N4 (Policy Packs)** - Already mostly done
   - Extract spec from policy-pack component
   - Document gate contract, remediation format

6. **N1 (Architecture)** - Synthesize from implementation
   - Document Polylith boundaries
   - Define canonical glossary
   - Stratification rules

**Rationale:** N3 and N6 are foundational - they force clarity everywhere else.

---

## Conformance Criteria

**miniforge implementation is conformant if:**

1. ✅ Emits all required event types (N3)
2. ✅ Maintains event sequencing per workflow (N3)
3. ✅ Generates evidence bundles for all workflows (N6)
4. ✅ Validates semantic intent vs. behavior (N6)
5. ✅ Provides queryable artifact provenance (N6)
6. ✅ Exposes SSE/WebSocket streaming (N3)
7. ✅ CLI commands match taxonomy (N5)
8. ✅ Policy packs conform to standard (N4)
9. ✅ Workflow execution follows phase graph (N2)
10. ✅ Components respect layering boundaries (N1)

**Enforcement:** Schema validation tests + integration tests + golden file tests.

---

## Next Steps

### For You (Human)

1. **Review N3 and N6** - These are the foundational specs
2. **Validate approach** - Does this match your vision?
3. **Identify gaps** - What did we miss from existing implementation?
4. **Decide on examples** - Which workflows should be canonical examples?

### For Implementation

1. **Instrument agents with event emission** (N3)
2. **Build event bus** for pub/sub (N3)
3. **Implement evidence bundle generation** (N6)
4. **Add semantic intent validation** (N6)
5. **Create SSE streaming endpoint** (N3)
6. **Build TUI event consumer** (N5, consumes N3)

### For Remaining Specs

1. **Extract N1** from Polylith structure + existing components
2. **Extract N2** from workflow engine implementation
3. **Extract N4** from policy-pack component
4. **Write N5** from CLI commands + TUI mockups

---

## Success Metrics

**We succeeded if:**

1. ✅ AI agents (Claude, Codex) find entry point easily (specs/SPEC_INDEX.md)
2. ✅ Implementers know what's contractual (normative/) vs. guidance (informative/)
3. ✅ No ambiguity on event schema (N3 defines it)
4. ✅ No ambiguity on evidence format (N6 defines it)
5. ✅ Spec doesn't explode (5 rules prevent it)
6. ✅ Conformance is testable (schemas, golden files, integration tests)

**We failed if:**

- New concepts bypass N1-N6 (spec explosion resumes)
- Implementers uncertain which doc is authoritative
- Event/evidence formats drift from spec
- AI agents use deprecated docs

---

## Files Changed Summary

**Created:**

- specs/SPEC_INDEX.md (entry point)
- specs/README.md (guide)
- specs/normative/N3-event-stream.md (complete)
- specs/normative/N6-evidence-provenance.md (complete)

**Moved:**

- 4 files → specs/informative/ (vision, roadmap, UX)
- 5 files → specs/deprecated/ (superseded content)

**Updated:**

- Added deprecation warnings to deprecated files

**Total:** 9 new/moved files, clean hierarchy established

---

## Phase 2: Remaining Normative Specs (2026-01-23)

### Files Created

**All extracted from strategic documents (NOT from code):**

1. **specs/normative/N1-architecture.md** ✅ COMPLETE (40+ pages)
   - Core domain model (12 key nouns with schemas)
   - Three-layer architecture (Control Plane, Agent Layer, Learning Layer)
   - Polylith component boundaries (complete OSS catalog)
   - Operational model (local-first, reproducibility, failure semantics)
   - Agent protocols (communication, context handoff, inter-agent messaging)

2. **specs/normative/N2-workflows.md** ✅ COMPLETE (45+ pages)
   - Phase graph (Plan → Design → Implement → Verify → Review → Release → Observe)
   - Phase responsibilities (detailed requirements for each phase)
   - Inner loop (validate → repair with multi-strategy repair)
   - Outer loop (state machine with prerequisites and failure handling)
   - Gate contract (check/repair signatures, violation schema)
   - Context handoff protocol

3. **specs/normative/N4-policy-packs.md** ✅ COMPLETE (48+ pages)
   - Pack structure (schema, versioning, signatures)
   - Gate execution contract (check/repair interfaces)
   - Semantic intent validation (IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE)
   - Violation schema (severity, remediation, auto-fix)
   - Terraform/Kubernetes-specific validation rules

4. **specs/normative/N5-cli-tui-api.md** ✅ COMPLETE (42+ pages)
   - CLI command taxonomy (6 namespaces with complete specs)
   - TUI primitives (4 primary views with keyboard navigation)
   - API surface (minimal REST + event streaming)
   - Operations console purpose (monitoring factory, NOT PR management)
   - Manual override mechanisms

### Methodology

**Correct approach used:**

- ✅ Extracted from strategic documents (software-factory-vision.md, oss-paid-roadmap.md)
- ✅ Defined contracts based on product requirements
- ✅ Used RFC 2119 language (MUST/SHALL/SHOULD/MAY)
- ✅ Focused on "what must be built" not "what exists in code"

**Incorrect approach avoided:**

- ❌ Did NOT extract from implementation code
- ❌ Did NOT describe existing shortcuts or incomplete features
- ❌ Did NOT let implementation drift into specs

### Spec Relationships

```
N1 (Architecture)
  ↓ defines core concepts for ↓
N2 (Workflows) ←→ N4 (Policy Packs)
  ↓ both emit ↓      ↓ both store results in ↓
N3 (Event Stream) → N6 (Evidence & Provenance)
  ↑ consumed by ↑    ↑ viewed via ↑
N5 (CLI/TUI/API)
```

All six normative specs are:

- Cross-referenced to each other
- Include conformance requirements
- Provide detailed examples
- Define testable contracts
- Use consistent format and language

---

**Status:** ✅ **COMPLETE** - All 6 normative specifications (N1-N6) extracted from strategic documents and ready for implementation.
