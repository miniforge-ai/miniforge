<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge Specifications

This directory contains the **canonical specifications** for the miniforge ecosystem.

## Three-Product Architecture

The miniforge ecosystem comprises three products built on a shared kernel:

- **MiniForge Core** — the governed workflow engine (kernel/runtime). Normative specs N1-N6 plus applicable indexed
  amendments define its contract. Any product built on the engine must conform to that applicable set.
- **Miniforge** — the autonomous software factory (SDLC product). Consumes MiniForge Core and adds SDLC-specific
  amendments and N7+ capabilities assigned by [SPEC_INDEX.md](SPEC_INDEX.md).
- **Data Foundry** — a generic ETL product built on MiniForge Core. Consumes the same N1-N6 engine contract with
  ETL-specific workflow packs, policy configurations, and only the amendments whose scope applies.

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
│   ├── N6-evidence-provenance.md  # ✅ Complete
│   ├── N7-... through N15-...     # Indexed extension specs
│   └── N*-delta-*.md              # Indexed amendments to a named base spec
├── informative/               # Guidance & references (non-normative)
│   ├── ux-tui-mockups.md
│   ├── ai-ux-flows.md
│   └── *.md                    # Informative contract, design, and system notes
└── deprecated/                # Superseded documents (historical reference)
    ├── AGENT_STATUS_STREAMING.md  → Superseded by N3
    ├── BUILD_PLAN_REVISED.md      → Content extracted to N2, N3, N6
    ├── OSS_PAID_ROADMAP.md        → Superseded; current strategy is private
    └── ...
```

## Normative vs. Informative

### Normative Specifications

**Core specs N1-N6** define the universal MiniForge Core contract. Indexed deltas amend a named base spec, and N7+
extensions define scoped product or capability contracts. The applicability table in [SPEC_INDEX.md](SPEC_INDEX.md) is
authoritative.

- Use RFC 2119 keywords: MUST, SHALL, SHOULD, MAY
- Breaking changes require version bump
- Implementations MUST conform to pass conformance tests
- Changes require careful review

**Core normative specs:**

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
- Include UX mockups and design/contract notes

## Rules to Prevent Spec Explosion

1. **Amend the owning spec by default** - Do not duplicate a contract
2. **Index every delta and extension** - Unindexed normative files are invalid
3. **Name scope and relationships** - Deltas name their base; extensions relate to N1-N6
4. **Centralize wire contracts** - Events/evidence/UX live in N3/N6/N5
5. **Roadmaps never contain contracts** - They link to specs

## For Implementers

**To implement miniforge:**

1. Read [SPEC_INDEX.md](SPEC_INDEX.md) for overview
2. Study N1-N6 and every amendment/extension applicable to the target product
3. Refer to informative docs for guidance
4. Pass conformance tests

**To propose changes:**

1. Check whether an indexed spec already owns the capability
2. Amend the owner unless a separately scoped delta/extension is approved
3. Add event/evidence/UX wire contracts to N3/N6/N5
4. Update `SPEC_INDEX.md` with scope and applicability

## For AI Agents (Claude, Codex, etc.)

**When building miniforge:**

1. **Always start with SPEC_INDEX.md** - It's your entry point
2. **The indexed applicable normative set is authoritative** - Core plus scoped amendments/extensions define MUST/SHALL
3. **Ignore deprecated/ directory** - Content superseded, use normative specs
4. **Informative docs provide context** - But don't define requirements

**If confused about a requirement:**

- Check N3 (events) or N6 (evidence) first - most implementation details live there
- Cross-reference: normative specs link to each other

## Conformance Testing

Normative specs are enforced by:

- **Schema validation tests** - Events, evidence, artifacts validate against schema
- **Golden file tests** - Example workflows/evidence as test fixtures
- **CLI contract tests** - Command interface stability
- **Integration tests** - End-to-end workflow execution

Conformance tests live alongside the Polylith components and project integrations
that implement each contract.

---

## Quick Reference

### What should I read for

**Understanding the product?**
→ [SPEC_INDEX.md](SPEC_INDEX.md) (1-paragraph summary)
→ [N1 - Core Architecture](normative/N1-architecture.md)

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

**OSS vs Paid split?**
→ [SPEC_INDEX.md](SPEC_INDEX.md) (applicability table)

---

**Version:** 0.2.0-draft
**Last Updated:** 2026-08-04
