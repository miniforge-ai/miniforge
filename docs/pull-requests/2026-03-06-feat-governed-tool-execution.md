# feat: Governed Tool Execution (N10)

**Branch:** `feat/governed-tool-execution`
**Base:** `main`
**Date:** 2026-03-06
**Layer:** Domain Model / Cross-Cutting
**Depends On:** N1 (Core Architecture), N4 (Policy Packs)

## Overview

Adds N10 — Governed Tool Execution, a consolidated normative spec for safe,
bounded, auditable execution of tool actions by agents against external systems.
Consolidates the core of the originally proposed N10-N14 spec suite into a
single actionable specification.

## Motivation

Miniforge agents will execute actions against real infrastructure — Kubernetes,
AWS, databases, CI/CD systems. Without governed execution:

- Agents could autonomously delete production databases
- No mechanical enforcement of blast radius limits
- No credential scoping or automatic expiration
- No audit trail linking agent intent to actual tool invocations
- No rollback verification before destructive actions

## Design Decisions

### Consolidation: five specs became one + two informative docs

The original N10-N14 proposal covered governed execution, capability broker,
fleet governance, operational validation, and incident diagnostics as five
separate normative specs. This was over-specified:

- N11 (Capability Broker) is inseparable from N10 — merged into §6-7
- N12 (Fleet Governance) is enterprise-only — deferred; trust levels
  included as informational §14
- N13 (Validation) had aspirational scope (TLA+, Shipyard, Tonic) — core
  (static analysis + dry-run) is normative §5; rest is informative
- N14 (Incident Diagnostics) is a workflow, not architecture — moved to
  informative doc

### Meta-agent enforcement over orchestrator enforcement

Action classification enforcement runs through the meta-agent advisory loop
feeding the orchestrator, not embedded in the orchestrator itself. The
orchestrator remains pure orchestration.

### Tool-declared action classification

Action classes (A-E) are properties of tool definitions in the tool registry,
not agent self-classification. Agents cannot reclassify actions. Tools
without declared classes default to Class D (fail safe).

### Policy packs for tool execution policies

Tool execution policies use the existing N4 policy pack infrastructure.
`:rule/applies-to` gains action class and environment targeting. No new
policy engine.

### Capsule isolation is configurable for Class A

Class A (read-only) capsule execution is optional but configurable, supporting
compliance frameworks that require full audit trails of all external reads.

### Third-party platforms are tools, not capabilities

Shipyard, Tonic, and similar platforms integrate as tool-registry entries
(`:mcp` or `:external` type) with capability tags. The tool registry's
existing capability matching selects appropriate providers. A capability
like `:validation/ephemeral-environment` may be fulfilled by any tool
that declares it.

## Changes in Detail

### specs/normative/N10-governed-tool-execution.md (new)

17 sections covering:

- §2: Operational Intent and IR
- §3: Action Classification (A-E) with tool-declared ownership
- §4: Verification Pipeline (3 required + adapter hooks)
- §5: Validation (static analysis + dry-run normative; adapter interface)
- §6: Capability Model (ephemeral, scoped, TTL, revocable)
- §7: Execution Capsules (sandboxed, isolated, destroyed after use)
- §8: Crown Jewel Protection (separation of authority, transitive protection)
- §9: Postcondition Monitoring (auto-rollback on failure)
- §10: Safety Invariants (SI-1 through SI-10)
- §11: External System Integration (MCP/SaaS as tools)
- §12: Audit Integration (N3 events, N6 evidence)
- §13: Conformance Requirements (TR/VF/VL/CP/EX/SF/AU)
- §14: Trust Level Progression (L0-L4)

### specs/informative/I-VALIDATION-STRATEGIES.md (new)

Extended validation strategies beyond normative requirements:
formal verification (TLA+/Quint), ephemeral environments (Shipyard),
synthetic data (Tonic), bounded canary execution.

### specs/informative/I-INCIDENT-DIAGNOSTICS.md (new)

Example workflow domain: incident detection, diagnostic investigation
graphs, remediation through N10, postcondition monitoring, learning loop.

### specs/SPEC_INDEX.md (updated)

Added N10 to normative specs, informative docs to operational workflows section.

## Testing Plan

- [ ] N10 markdown renders correctly (tables, code blocks, section numbering)
- [ ] All conformance requirement IDs (N10.TR/VF/VL/CP/EX/SF/AU) are unique
- [ ] Cross-references to N1, N3, N4, N6 are accurate
- [ ] Safety invariants SI-1 through SI-10 are consistent with §3-§8
- [ ] Tool registry schema extension is compatible with existing ToolConfig
- [ ] Policy rule examples use valid N4 rule schema
- [ ] Informative docs reference correct N10 sections

## Checklist

- [x] N10 normative spec
- [x] I-VALIDATION-STRATEGIES informative doc
- [x] I-INCIDENT-DIAGNOSTICS informative doc
- [x] SPEC_INDEX.md updated
- [x] PR documentation
