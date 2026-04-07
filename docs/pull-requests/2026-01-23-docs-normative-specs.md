# PR: Normative Specifications (N1-N6)

**Branch:** `docs/normative-specs`
**Date:** 2026-01-23

## Summary

Collapses specification sprawl into 6 canonical normative specifications plus supporting documentation hierarchy.
Extracts complete contractual requirements from strategic documents (NOT code) to define what miniforge MUST be.
Establishes governance rules to prevent future spec explosion.

**Total:** ~270 pages of normative contracts defining the complete miniforge product.

## Changes

### New Files (Specification Infrastructure)

- `specs/SPEC_INDEX.md` - **Authoritative entry point** with:
  - Canonical 1-paragraph product description
  - Index of all 6 normative specs (N1-N6)
  - Index of informative docs and examples
  - 5 governance rules to prevent spec explosion

- `specs/README.md` - Guide for implementers and AI agents with:
  - Directory structure explanation
  - Normative vs. informative distinction
  - Quick reference ("What should I read for...")
  - Conformance testing requirements

### New Files (Normative Specifications)

#### N1 - Core Architecture & Concepts (40+ pages)

- `specs/normative/N1-architecture.md`
- **Defines:** Core domain model (12 key nouns), three-layer architecture (Control Plane → Agent Layer →
  Learning Layer), Polylith component boundaries, operational model (local-first, reproducibility,
  failure semantics), agent communication protocols
- **Key contracts:** Component interface requirements, agent communication protocol, layering rules

#### N2 - Workflow Execution Model (45+ pages)

- `specs/normative/N2-workflows.md`
- **Defines:** Phase graph (Plan → Design → Implement → Verify → Review → Release → Observe),
  phase responsibilities, inner loop (validate → repair with multi-strategy), outer loop state machine,
  gate contract, context handoff protocol
- **Key contracts:** Phase execution requirements, inner loop termination conditions, gate function signatures

#### N3 - Event Stream & Observability Contract (52 pages)

- `specs/normative/N3-event-stream.md`
- **Defines:** Event envelope schema, 9 event type categories (workflow, agent, status, subagent, tool, LLM,
  messages, milestone, gate), ordering guarantees, SSE/WebSocket streaming API, throttling rules,
  performance requirements (<10ms emit, <100ms stream)
- **Key contracts:** Event schema validation, sequence number monotonicity, subscription API
- **Most leverageful spec:** Powers UI, debugging, analytics, and future learning

#### N4 - Policy Packs & Gates Standard (48+ pages)

- `specs/normative/N4-policy-packs.md`
- **Defines:** Pack structure (schema, versioning, signatures), gate execution contract (check/repair interfaces),
  semantic intent validation (IMPORT/CREATE/UPDATE/DESTROY/REFACTOR/MIGRATE), violation schema,
  Terraform/Kubernetes-specific validation
- **Key contracts:** Policy pack manifest schema, rule detection protocol, gate function signatures
- **Why critical:** Prevents accidental infrastructure changes (declared IMPORT but code creates → violation)

#### N5 - Interface Standard: CLI/TUI/API (42+ pages)

- `specs/normative/N5-cli-tui-api.md`
- **Defines:** CLI command taxonomy (6 namespaces: init, workflow, fleet, policy, evidence, artifact),
  TUI primitives (4 primary views), API surface (minimal REST + event streaming), operations console purpose
  (monitoring factory, NOT PR management), manual override mechanisms
- **Key contracts:** CLI command stability, TUI keyboard navigation, API endpoints
- **Critical mental model:** Operations console monitors the factory, it's NOT the product

#### N6 - Evidence & Provenance Standard (47 pages)

- `specs/normative/N6-evidence-provenance.md`
- **Defines:** Evidence bundle schema (intent → phases → validation → outcome), artifact provenance
  (source inputs, tool executions, hashes), semantic intent validation rules, queryable provenance API,
  compliance metadata (SOCII/FedRAMP)
- **Key contracts:** Evidence bundles MUST be immutable, all artifacts MUST have provenance,
  semantic validation MUST check intent vs. behavior
- **Why critical:** Enables credibility and compliance, complete audit trail from requirement → outcome

### Files Reorganized

**Moved to specs/informative/** (non-normative guidance):

- `SOFTWARE_FACTORY_VISION.md` → `specs/informative/software-factory-vision.md`
- `PRODUCT_STRATEGY.md` → `specs/informative/oss-paid-roadmap.md`
- `UX_DESIGN_SPEC.md` → `specs/informative/ux-tui-mockups.md`
- `UX_AI_FEATURES.md` → `specs/informative/ai-ux-flows.md`

**Moved to specs/deprecated/** (superseded content):

- `AGENT_STATUS_STREAMING.md` (content extracted to N3, added deprecation warning)
- `BUILD_PLAN_REVISED.md` (content extracted to N2, N3, N6)
- `BUILD_PLAN.md` (outdated)
- `OSS_PAID_ROADMAP.md` (superseded by informative/oss-paid-roadmap.md)
- `REVISED_TIMELINE.md` (merged into roadmap)

**Moved to specs/archived/** (historical specs):

- All 23 files from `docs/specs/` → `specs/archived/`
- Early product and component specifications (`.spec` and `.spec.edn` files)
- Included: architecture.spec, miniforge.spec, workflow-*.spec.edn, cli-*.spec.edn, policy-pack.spec, etc.
- Added `specs/archived/README.md` explaining their historical nature and migration to normative specs

### Summary Documents Created

- `docs/SPEC_SYNTHESIS_SUMMARY.md` - Complete process documentation (methodology, decisions, phase breakdown)
- `docs/NORMATIVE_SPECS_COMPLETE.md` - Reference for all 6 specs (dependencies, conformance, priority)

## Design Decisions

### 1. Extract from Strategic Documents, NOT Code

**Critical decision:** All normative specs extracted from strategic vision documents
(software-factory-vision.md, oss-paid-roadmap.md), NOT from implementation code.

**Rationale:** Specs define what MUST be built, not what happens to exist in partially-built product.
Code conforms to specs, not reverse. This prevents implementation shortcuts and drift from being codified
as requirements.

### 2. Event Stream as Product Surface, Not Logging

**Normative requirement (N3):** Event stream is product infrastructure that powers UI, debugging, analytics,
and learning.

**Rationale:** Forces clarity on observability contracts, enables replay/debugging, provides foundation
for future learning systems.

### 3. Semantic Intent Validation is Mandatory

**Normative requirement (N6):** Workflows MUST declare intent (`:import`, `:create`, `:update`, `:destroy`),
implementations MUST validate declared vs. actual behavior, critical mismatches MUST block merge.

**Rationale:** Makes autonomous workflows credible to platform/security teams.
Prevents "declared IMPORT but actually CREATE" violations that would destroy production infrastructure.

### 4. Evidence Bundles are Immutable

**Normative requirement (N6):** Evidence bundles MUST NOT be modified after creation, content hashes MUST be
calculated for all artifacts, bundles MUST link to event stream.

**Rationale:** Immutability enables compliance (SOCII, FedRAMP), debugging, and trust.

### 5. Exactly 6 Normative Specs (No More)

**Governance rule:** Only N1-N6 can contain MUST/SHALL language. New concepts amend existing specs.
No new normative spec files.

**Rationale:** Prevents spec explosion. Forces clarity and consolidation.

### 6. RFC 2119 Language Discipline

**Normative specs:** MUST use RFC 2119 keywords (MUST, SHALL, SHOULD, MAY, MUST NOT, SHALL NOT)
**Informative docs:** MUST NOT use RFC 2119 keywords

**Rationale:** Clear boundary between contractual requirements and guidance.

## Specification Dependencies

```text
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

**Key insight:** N3 (Event Stream) and N6 (Evidence & Provenance) are the most leverageful specs.
Everything else builds on these foundations.

## Conformance Criteria

miniforge implementation is conformant if it satisfies ALL requirements in all 6 specs:

1. **N1 conformance:** Components respect layer boundaries, provide interface namespaces,
   use defined agent protocols
2. **N2 conformance:** Executes all required phases, inner loop validates/repairs until gates pass,
   outer loop follows state machine
3. **N3 conformance:** Emits all required event types, maintains per-workflow sequencing,
   event replay is deterministic, streaming endpoints work
4. **N4 conformance:** Policy packs validate against schema, gates implement check/repair contract,
   semantic intent validation detects mismatches
5. **N5 conformance:** CLI commands match taxonomy, TUI provides required views, API exposes minimal endpoints,
   evidence viewer shows full audit trail
6. **N6 conformance:** Evidence bundles generated for all workflows, all artifacts have provenance,
   semantic validation checks intent vs. behavior

**Enforcement:** Schema validation tests + integration tests + conformance test suite.

## Implementation Priority

**Recommended build order:**

1. **N3 (Event Stream)** - Foundation for everything (UI, debugging, analytics)
2. **N6 (Evidence & Provenance)** - Credibility layer (audit trail, compliance)
3. **N2 (Workflow Execution)** - Core engine (already mostly implemented, extract spec)
4. **N4 (Policy Packs)** - Validation layer (already mostly implemented, extract spec)
5. **N5 (CLI/TUI/API)** - User interface (consumes N3 events, displays N6 evidence)
6. **N1 (Architecture)** - Synthesize from implementation (documents what exists)

**Rationale:** N3 and N6 are foundational - they force clarity everywhere else.

## Governance Rules (Prevent Spec Explosion)

1. **No new normative spec files** - Only amend N1-N6
2. **Every new concept must land in N1** - Or it's not real
3. **Every new runtime datum must land in N3 or N6** - Or it's unobservable
4. **Every UX feature must point to a contract** - Or it's just a mock
5. **Roadmaps never contain contracts** - They link to specs

## Impact

**Before:** 9+ documents scattered across repo, unclear what's contractual vs. informative
**After:** 6 normative specs define all MUST/SHALL requirements, clear hierarchy, stable entry point

**Benefits:**

- AI agents (Claude, Codex) have stable entry point (specs/SPEC_INDEX.md)
- Implementers know what's authoritative (normative/) vs. guidance (informative/)
- No ambiguity on schemas (events, evidence, policy packs all defined)
- Conformance is testable (schema validation, golden files, integration tests)
- Spec explosion prevented by governance rules

## Next Steps

1. **Create conformance test suite** - Validate implementations against N1-N6
2. **Create golden file examples** - Reference workflows/evidence bundles
3. **Build event stream implementation** - N3 contracts
4. **Build evidence bundle generation** - N6 contracts
5. **Extract remaining examples** - specs/examples/{workflows,evidence,policy-packs}

## Testing

**Validation performed:**

- All specs cross-reference each other correctly
- All deprecated files have warnings pointing to normative specs
- All informative docs use descriptive language (no MUST/SHALL)
- All normative specs use RFC 2119 keywords consistently
- Spec dependency graph is acyclic

**Conformance testing:** Not applicable (specs define requirements, conformance tests validate implementations)

## Dependencies

**Source documents:**

- `specs/informative/software-factory-vision.md` - Product vision
- `specs/informative/oss-paid-roadmap.md` - Roadmap
- `specs/deprecated/BUILD_PLAN_REVISED.md` - Historical implementation notes

**External standards:**

- RFC 2119 - Requirement levels (MUST/SHALL/SHOULD/MAY)
- Polylith architecture pattern
- SSE (Server-Sent Events) specification
- WebSocket protocol
