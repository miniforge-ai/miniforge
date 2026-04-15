<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge Specifications

This directory contains the **canonical specifications** for the miniforge ecosystem.

## Three-Product Architecture

The miniforge ecosystem comprises three products built on a shared kernel:

- **MiniForge Core** — the governed workflow engine (kernel/runtime). Normative specs N1-N6 define its contract:
  architecture, workflow execution, event stream, policy packs, interface standards, and evidence/provenance. Any
  product built on the engine must conform to these six specs.
- **Miniforge** — the autonomous software factory (SDLC product). Consumes MiniForge Core and adds SDLC-specific
  capabilities defined in N5 and N7-N10 (fleet mode, observability control, external PR integration, governed tool
  execution).
- **Data Foundry** — a generic ETL product built on MiniForge Core. Consumes the same N1-N6 engine contract with
  ETL-specific workflow packs and policy configurations.

## Entry Point

**Start here:** [SPEC_INDEX.md](SPEC_INDEX.md)

The spec index is the authoritative map of all normative and informative documentation.

## Directory Structure

```text
specs/
├── SPEC_INDEX.md              # START HERE - Complete spec catalog
├── README.md                  # This file
├── normative/                 # Contractual requirements (MUST/SHALL)
│   ├── N1-architecture.md
│   ├── N2-workflows.md
│   ├── N3-event-stream.md    # ✅ Complete
│   ├── N4-policy-packs.md
│   ├── N5-cli-tui-api.md
│   └── N6-evidence-provenance.md  # ✅ Complete
├── informative/               # Guidance & references (non-normative)
│   ├── software-factory-vision.md
│   ├── oss-paid-roadmap.md
│   ├── ux-tui-mockups.md
│   └── ai-ux-flows.md
├── examples/                  # Reference implementations
│   ├── workflows/
│   ├── evidence/
│   └── policy-packs/
└── deprecated/                # Superseded documents (historical reference)
    ├── AGENT_STATUS_STREAMING.md  → Superseded by N3
    ├── BUILD_PLAN_REVISED.md      → Content extracted to N2, N3, N6
    ├── OSS_PAID_ROADMAP.md        → Superseded by informative/oss-paid-roadmap.md
    └── ...
```

## Normative vs. Informative

### Normative Specifications (N1-N6) — MiniForge Core Contract

**Normative specs N1-N6** define the **contractual requirements** for MiniForge Core — the shared engine that both
  Miniforge (SDLC) and Data Foundry consume.

- Use RFC 2119 keywords: MUST, SHALL, SHOULD, MAY
- Breaking changes require version bump
- Implementations MUST conform to pass conformance tests
- Changes require careful review

**Current normative specs:**

1. **N1 - Core Architecture** (Draft) - Conceptual model, layering, Polylith boundaries
2. **N2 - Workflow Execution** (Draft) - Phase graph, inner loop, gate contract
3. **N3 - Event Stream** (Draft) ✅ - Event protocol, streaming API, observability
4. **N4 - Policy Packs** (Draft) - Policy pack standard, gate execution
5. **N5 - CLI/TUI/API** (Draft) - User interface contract, command taxonomy
6. **N6 - Evidence & Provenance** (Draft) ✅ - Evidence bundles, artifact provenance, semantic validation

### Informative Documentation

**Informative docs** provide guidance, context, and examples but do NOT define requirements.

- Use descriptive language (no MUST/SHALL)
- Can change without version bumps
- Inform normative specs but don't constrain them
- Include UX mockups, guides, roadmaps, vision documents

## Rules to Prevent Spec Explosion

1. **No new normative spec files** - Only amend N1-N6
2. **Every new concept must land in N1** - Or it's not real
3. **Every new runtime datum must land in N3 or N6** - Or it's unobservable
4. **Every UX feature must point to a contract** - Or it's just a mock
5. **Roadmaps never contain contracts** - They link to specs

## For Implementers

**To implement miniforge:**

1. Read [SPEC_INDEX.md](SPEC_INDEX.md) for overview
2. Study normative specs N1-N6 (start with N3, N6)
3. Refer to informative docs for guidance
4. Use examples for reference implementations
5. Pass conformance tests

**To propose changes:**

1. Check if concept belongs in existing N1-N6
2. If new concept → amend N1 first
3. If new runtime data → amend N3 or N6
4. If new UX feature → link to contract in N3/N5/N6
5. Update examples to demonstrate change

## For AI Agents (Claude, Codex, etc.)

**When building miniforge:**

1. **Always start with SPEC_INDEX.md** - It's your entry point
2. **Normative specs (N1-N6) are authoritative** - These define MUST/SHALL
3. **Ignore deprecated/ directory** - Content superseded, use normative specs
4. **Informative docs provide context** - But don't define requirements
5. **Examples show conformance** - Use them as patterns

**If confused about a requirement:**

- Check N3 (events) or N6 (evidence) first - most implementation details live there
- Cross-reference: normative specs link to each other
- Check examples/ for concrete demonstrations

## Conformance Testing

Normative specs are enforced by:

- **Schema validation tests** - Events, evidence, artifacts validate against schema
- **Golden file tests** - Example workflows/evidence as test fixtures
- **CLI contract tests** - Command interface stability
- **Integration tests** - End-to-end workflow execution

See `miniforge/tests/` for conformance test suite.

---

## Quick Reference

### What should I read for

**Understanding the product?**
→ [SPEC_INDEX.md](SPEC_INDEX.md) (1-paragraph summary)

**Building agents?**
→ [N3 - Event Stream](normative/N3-event-stream.md) (emit events)
→ [N6 - Evidence & Provenance](normative/N6-evidence-provenance.md) (create evidence)

**Building the CLI/TUI?**
→ [N5 - CLI/TUI/API](normative/N5-cli-tui-api.md)
→ [N3 - Event Stream](normative/N3-event-stream.md) (consume events)
→ [informative/ux-tui-mockups.md](informative/ux-tui-mockups.md) (visual design)

**Building policy packs?**
→ [N4 - Policy Packs](normative/N4-policy-packs.md)

**Understanding workflow execution?**
→ [N2 - Workflow Execution](normative/N2-workflows.md)
→ [N6 - Evidence & Provenance](normative/N6-evidence-provenance.md) (semantic validation)

---

**Version:** 0.1.0-draft
**Last Updated:** 2026-01-23
