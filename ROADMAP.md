<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Roadmap

Last updated: 2026-04-13

## Current Status

**Alpha** -- actively developed and dogfooded daily. The core SDLC pipeline
(spec in, PR out) works end-to-end for Clojure projects. 530 PRs merged,
87 components, 187k LOC, 71k LOC of tests, 313 test files.

What works today:

- Full pipeline: spec -> explore -> plan -> implement -> verify -> review -> release -> PR
- DAG orchestration with parallel task execution and resumability
- Intelligent model selection (16 models, 4 providers)
- Policy gates at every phase transition (syntax, lint, no-secrets, tests-pass, coverage)
- Evidence bundles with provenance chains for traceability
- Meta-agent learning loop (miniforge improving its own codebase)
- CLI (`mf run`, `mf workflow`, `mf tui`) via Babashka

## Normative Spec Progress

The system is defined by 11 normative specs (N1-N11). Current overall
completion: approximately 45-50%.

| Spec | Area | Complete | What's Working | Key Gaps |
|------|------|----------|----------------|----------|
| N1 | Core Architecture | ~80% | Structure, state machine, agents | Event wiring gaps; trust enforcement unverified end-to-end |
| N2 | Workflow Execution | ~85% | Phases, inner loop, DAG, gates | Budget enforcement, capability contracts, resumption completeness |
| N3 | Event Stream | ~50% | Core infra, append-only, subscriptions | Many event types not emitted (gate, tool, inter-agent, milestone, OPSV, ETL) |
| N4 | Policy Packs & Gates | ~55% | Pack schema, check/repair, severity | K8s diff parsing, knowledge-safety pack, pack dependency graph |
| N5 | CLI / TUI / API | ~45% | CLI base, TUI engine | Many commands not wired; transport decision (WebSocket vs REST+SSE) pending |
| N6 | Evidence & Provenance | ~60% | Core bundles, provenance chain | Sensitive data scanning, compliance metadata, OPSV evidence types |
| N7 | OPSV | ~0% | Infrastructure exists to support it | Entirely unimplemented: experiment packs, convergence, verification |
| N8 | Observability Control | ~10% | Event subscription | No RBAC, multi-party approval, privacy levels, OTel, W3C Trace Context |
| N9 | External PR Integration | ~35% | PR trains, repo DAG foundations | PR Work Item model, automation tiers, provider-native checks, credentials |
| N10 | Governed Tool Execution | ~50% | Tool registry | Sandboxing, approval flows, audit logging for invocations |
| N11 | Task Capsule Isolation | ~50% | DAG executor isolation primitives | Artifact export, cleanup reliability, timeout enforcement (4 active specs) |

## Near-Term Priorities (Next 4-8 Weeks)

These are the Tier 1 items from the progress review. All have corresponding
work specs in `work/`.

1. **Integration test coverage** (`oss-integration-test-coverage.spec.edn`)
   -- PR lifecycle (currently zero tests), release executor, gate pipeline,
   agent response parsing, metrics accumulation, evidence bundle assembly.
   Primary OSS readiness blocker.

2. **Event type completeness** (`n03-event-type-completeness.spec.edn`)
   -- Emit all N3-specified event types (gate, tool, inter-agent, milestone,
   ETL, listener). Unblocks N8 observability and full event-driven workflows.

3. **CLI wiring audit** (`n05-cli-command-wiring.spec.edn`)
   -- Audit all N5-specified commands and wire them to existing components.
   Many components exist but lack CLI exposure.

4. **Capsule isolation** (4 in-progress specs)
   -- Artifact export before destroy, cleanup reliability on failure paths,
   timeout enforcement, execution mode evidence. Currently in progress.

5. **Workflow redesign** (`workflow-redesign-use-case-targeted.spec.edn`)
   -- Replace complexity-based workflow selection (simple/lean/canonical) with
   use-case targeted workflows that include safety gates by default.

## Medium-Term Goals (3-6 Months)

### Production Readiness

- **Sensitive data scanning** (`n06-sensitive-data-scanning.spec.edn`)
  -- Detect credentials and PII in evidence bundles before storage.
- **OCI governance foundation** (`n08-oci-governance.spec.edn`)
  -- RBAC roles/permissions and control action audit logging.
- **PR Work Item model** (`n09-pr-work-item-model.spec.edn`)
  -- Deterministic readiness, risk assessment, automation tiers (0-3).
- **Knowledge-safety pack** (`n04-knowledge-safety-pack.spec.edn`)
  -- Prompt injection detection, tripwire system, trust enforcement.

### Reliability

- **Reliability network** (RN-01 through RN-16)
  -- Failure taxonomy, SLI/SLO engine, degradation modes, autonomy model,
  compensation protocol, safe-mode, tool semantics, evaluation pipeline.

### TUI Supervisory Surface

- **TUI workstreams** (WS1-WS5)
  -- Supervisory domain model, durable startup, monitor mode, governance
  surface, attention/intervention. Monitor-first paradigm over command interface.

### Ecosystem

- **GitLab support** (`gitlab-support.spec.edn`)
  -- Merge request lifecycle parity with GitHub PR support.
- **Backend failover** (`backend-failover.spec.edn`)
  -- LLM rate-limit failover across providers.
- **Policy pack extensibility** (`policy-pack-extensibility.spec.edn`)
  -- Policy packs as the sole extension point for customization.

### Vision Completion

- **OPSV** (`n07-opsv-workflow.spec.edn`, `n07-opsv-converge-verify-actuate.spec.edn`)
  -- Operational Policy Synthesis: experiment packs, convergence loops,
  verification suites. Largest single gap, timeline dependent on core stability.
- **OTel alignment** (`n08-otel-trace-context.spec.edn`)
  -- OpenTelemetry and W3C Trace Context propagation.
- **Provider-native checks** (`n09-provider-native-checks.spec.edn`)
  -- Publish GitHub Check Runs from policy evaluation results.

## How to Contribute

All roadmap items are backed by work specs in the `work/` directory. Each
`.spec.edn` file describes the problem, acceptance criteria, and tasks.

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) for setup, conventions, and the PR process.
2. Pick a work spec from `work/` that interests you. Specs prefixed with
   `n03-`, `n04-`, etc. map to normative spec areas. `oss-` and `rn-` prefixes
   indicate OSS readiness and reliability work respectively.
3. Check `work/in-progress/` to avoid duplicating active work.
4. Open an issue or discussion referencing the spec before starting large items.

Priority labels in specs: `:high`, `:medium`, `:low`. Start with high-priority
items from the near-term list above if you want maximum impact.
