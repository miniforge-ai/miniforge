---
title: Quality Readiness Assessment Workflow
description: Build a Miniforge workflow that evaluates a repository against a 9-domain quality-readiness rubric,
produces evidence-backed domain scores, identifies blockers, and routes output to human review or remediation planning
workflows.
acceptance_criteria: Workflow scores all 9 domains with evidence citations, produces red/orange/yellow/green status per
domain, detects hard blockers, routes to proceed/human-vet/plan-remediation/block, emits handoff artifacts
tags: [quality, readiness, assessment, rubric, governance]
---

## Spec Reference

Normative workflow contract: `specs/informative/miniforge_quality_readiness_workflow_spec_v2.md`

This document covers the implementation design. For requirements (MUST/SHALL/SHOULD), see the spec.

---

## What It Is

A governance workflow that evaluates a repository, service, or workflow against a structured
readiness rubric and produces a scored assessment with routing decisions. Think of it as
converting a senior engineer's release-readiness checklist into a machine-runnable, evidence-backed
evaluation pass.

Output: red/orange/yellow/green scorecard across 9 domains, with blockers, confidence ratings,
and a routing decision (`:proceed` / `:proceed-with-conditions` / `:human-vet` / `:plan-remediation` / `:block`).

---

## Dogfood Targets

The first two runs against the miniforge codebase itself:

1. **Miniforge specs** — run the QRA workflow against `specs/normative/*.md` and
   `specs/informative/*.md` using the `:miniforge-workflow` profile. Evaluates whether
   the specs themselves meet readiness criteria (product clarity, test strategy documented,
   operational considerations addressed, etc.).

2. **Miniforge codebase** — run against the full repo using `:platform-or-infra` profile.
   Surfaces gaps in observability, security posture, test coverage evidence, and operational
   readiness documentation.

Both are meaningful governance passes that would take hours for a human reviewer. The QRA
workflow does it automatically and produces a handoff artifact for human vetting of the
low-confidence or low-score domains.

---

## Architecture

### Workflow decomposition (per spec §14)

Four cooperating sub-workflows, each a Miniforge phase:

| Sub-workflow | Phase | Inputs | Outputs |
|---|---|---|---|
| `quality-readiness-inventory` | Evidence Extraction | repo-path, artifact-paths | Normalized evidence graph, target classification |
| `quality-readiness-score` | Scoring | Evidence graph, profile, weights | Domain scores, blockers, confidence ratings |
| `quality-remediation-plan` | Planning | Domain scores, deficits | Remediation backlog, handoff artifacts |
| `quality-vetting-gate` | Routing | Scorecard | Routing decision, review packet |

Natural home: `workflow-chain-quality-readiness` (parallel to `workflow-chain-software-factory`).

### Stage state machine (per spec §15)

```text
:inventory → :classify → :extract-evidence → :score-domains
          → :synthesize → :route → :emit-artifacts → :done
```

Each transition is gated on the previous stage's output being present.

---

## Evidence Extraction

The inventory phase uses `repo-index` as its primary file discovery mechanism,
then augments with:

- CI/CD configs (`ci-configs` input paths)
- Test results and coverage reports (discovered from CI artifacts or build output dirs)
- Spec/doc files (`specs/`, `docs/`, ADRs, PRDs)
- Observability hooks (grepped from source: logging calls, metrics emissions, health endpoints)
- Security signals (secret patterns absent, dependency scanning config present, authz patterns)

The compliance scanner delta report (when available) is loaded as a pre-built evidence artifact
for the `:quality-evidence` domain rather than re-running the scan.

---

## Scoring

The LLM evaluator receives, per domain, the relevant evidence subset plus the criterion
definitions from the spec. It returns:

```edn
{:criterion-id keyword
 :score integer         ; 0-4 per spec §7.1
 :confidence keyword    ; :low :medium :high
 :status keyword        ; :red :orange :yellow :green
 :reason string
 :evidence [evidence-ref]
 :missing-evidence [string]
 :negative-evidence [string]}
```

Domain score = weighted average of criterion scores. Status thresholds per spec §7.3.

Confidence is tracked separately from score throughout — a `:green` score with `:low`
confidence routes to `:human-vet`, not `:proceed`.

---

## Profile System

Profiles are loaded from the standards submodule (`.standards/`) or supplied inline.
The spec defines 6 built-in profiles (§9.1). The `:miniforge-workflow` profile is the
self-evaluation profile for Miniforge's own workflows and specs.

Initial profile definitions ship as EDN files under `components/quality-readiness/resources/profiles/`.

---

## Relationship to Compliance Scanner

The compliance scanner (`docs/design/compliance-scanner.md`) is a prerequisite. Its delta
report feeds the `:quality-evidence` domain as a structured evidence artifact. Run order:

```text
compliance-scan → (delta report) → quality-readiness-assess
```

The compliance scan can be skipped if a recent cached report exists (within the staleness window).

---

## Components

### New

| Component | Role |
|---|---|
| `quality-readiness` | Core: evidence extraction, scoring, routing, artifact emission |
| `workflow-chain-quality-readiness` | Wires inventory → score → plan → gate phases |

### Extended

| Component | Extension |
|---|---|
| `policy-pack` | Profile loader for quality readiness profiles (EDN format distinct from `.mdc`) |
| `phase-software-factory` | Evidence extraction phase variant |

### Used As-Is

`repo-index` (evidence discovery), `dag-executor` (parallel evidence extraction),
`compliance-scanner` (pre-built evidence artifact), `workflow` (lifecycle),
`event-stream` (telemetry per spec §17).

---

## Milestones

| Milestone | Delivers |
|---|---|
| Q1: inventory + score | Evidence extraction phase + LLM scoring; produces EDN scorecard |
| Q2: routing + handoff artifacts | Routing gate + human review packet + remediation plan handoff |
| Q3: run on miniforge | First dogfood runs: specs + codebase; baseline stored |

Q1 starts after compliance-scanner M2 ships (so compliance delta report is available as evidence input).
