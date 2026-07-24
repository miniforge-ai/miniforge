<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N15 — Collective-Cognition Evaluation Harness

**Version:** 0.1.0-draft
**Date:** 2026-07-22
**Status:** Draft
**Conformance:** MUST (core protocol); workspace-conditional sections per §0.4
**Class:** Extension spec (N7+)
**Implementation home:** minibench (kernel, adapters, fixtures) and workbench-contract; gates consumed by N14 §11

---

## 0. Status and Scope

### 0.1 Purpose

This specification defines how multi-agent architecture claims are evaluated in minibench:
which hypotheses are separable, which conditions are compared, how budgets are matched, and
which pre-registered gates govern N14 staging. Its premise: most reported multi-agent gains
are explained by test-time compute scaling (best-of-N sampling plus synthesis). A new
architecture earns adoption only by beating matched-budget baselines, not single-condition
demonstrations.

### 0.2 Relationship to existing contracts

- **workbench-contract**: conditions are `RunVariant`s. The architecture condition is the
  `workflow` VariantRef; `axes` carries `budget_tier`, `ablation`, `scheduler`,
  `population`; replicates share `label` with distinct `run_id`.
- **minibench kernel**: `compare` renders the condition matrix; `diff` provides the
  regression gate against the incumbent condition.
- **miniforge N14**: supplies conditions C6/C7 (workspace, workspace-ablated) and the
  per-run accounting this spec consumes; §8 gates govern N14 stage promotion.
- **Methods review 2026-07-02**: gaps 1–4 (replication, comparability preconditions,
  provenance stamping, absence-as-divergence) are normative requirements here (§5).

### 0.3 Non-goals

- Short-answer reasoning benchmarks (MMLU-class). The task class here is long-horizon (§6).
- Leaderboard publication. This harness decides internal architecture questions.
- Cross-run knowledge reuse measurement (H5) — registered but deferred (§2).

### 0.4 Lifecycle coupling to N14

The harness is the instrument, not the hypothesis. Most of this specification is
architecture-agnostic and remains normative regardless of gate outcomes: the budget
protocol (§4), replication/comparability/provenance (§5), the task class (§6), metrics
(§7), the effect rule and pre-registration (§8.1, §8.3), and regression gating (§8.6).
Conditions C1–C5 remain the standing control set for any future architecture claim.

Workspace-conditional content — C6/C7 (§3), the ablation delta (§8.2), and Gates G0/G1
(§8.4–§8.5) — shares N14's speculative status (N14 §0.4). If Gate G0 fails and N14
demotes to Informative, these sections MUST be re-marked as an informative annex recording
the negative result. A later architecture claim MUST re-instantiate its own conditions,
ablation pairing, and pre-registered gates under the surviving core protocol.

## 1. Terminology

- **Condition**: a complete architecture configuration (C1–C7, §3) run against a task.
- **Budget tier**: a pinned resource allowance (normalized cost + wall-clock cap).
- **Replicate**: an independent run of (condition, task, tier); same variant `label`,
  distinct `run_id`.
- **Matched comparison**: conditions compared only within the same (task, tier).
- **Overhead fraction**: coordination tokens (workspace operations, projection rendering,
  inter-agent relay) divided by total participant tokens.
- **Ablation pair**: C6 vs. C7 on identical (task, tier, seed inputs).
- **Gate**: a pre-registered decision rule over experiment outcomes.

## 2. Hypothesis registry

| Id | Hypothesis | Separating comparison |
|---|---|---|
| H1 | Sampling: additional inference alone explains gains | C1 vs. C2 |
| H2 | Diversity: heterogeneous models beat same-compute homogeneous sampling | C2 (best single model, N samples) vs. C3 (N models) |
| H3 | Aggregation: structured fusion beats pick-best | C2 vs. C2s; C4 vs. C3 |
| H4 | Collective cognition: persistent typed shared state beats equal-compute sampling, fusion, debate, and relay | C6 vs. best of C1–C5; C6 vs. C7 |
| H5 | Reuse: cross-run persistent knowledge adds capability (deferred; requires N14 post-v1 cross-run workspaces) | reserved |

H4 is the claim under test. H1–H3 conditions are controls; without them, any C6 gain is
attributable to compute scaling. Published external results bearing on H1–H3 (sampling-and-
voting scaling; equal-budget single-agent parity; single-best-model self-mixture beating
multi-model mixtures) justify treating H1–H3 as the default explanation — the burden of
proof sits on H4.

## 3. Conditions (normative set)

| Id | Condition | Definition |
|---|---|---|
| C1 | single-pass | One agent, one attempt, full tier budget |
| C2 | best-of-N | One model, N independent attempts, verifier-selected winner |
| C2s | best-of-N + synthesis | C2's model and attempt count, with a synthesis pass replacing the verifier pick (isolates aggregation from diversity) |
| C3 | independent + synthesis | N heterogeneous agents, independent attempts, one synthesis pass (mixture-of-agents proper) |
| C4 | sequential debate | r debate rounds (r pinned per manifest), each agent sees prior prose, judge closes |
| C5 | static pipeline | Fixed role relay (interpreter → proposer → implementer → verifier → synthesizer), transcript handoff, no shared graph |
| C6 | workspace | N14 deliberation run (stage per experiment manifest) |
| C7 | workspace-ablated | C6 with `cross_visibility: none` (N14 §4.4); all else identical |

Requirements: every experiment MUST include C1, C2, and C6; C7 MUST be included whenever C6
is (paired). C2s is REQUIRED in any experiment claiming an H3 result and OPTIONAL otherwise. C5 exists to separate
"roles + relay" from "shared typed state" — if C6 only
matches C5, the graph is not earning its overhead. Population pinning: each condition's
manifest MUST enumerate exact model ids, role bindings, and prompts; C2's model MUST be the
strongest single model available to C3/C6 populations.

## 4. Budget protocol

- **Currency**: normalized cost computed from a price table pinned (hashed) in the
  experiment manifest, plus a wall-clock ceiling. Token counts are recorded but cost is the
  matching currency (heterogeneous populations make raw tokens incomparable).
- **Tiers**: every experiment MUST run ≥2 tiers. Results are reported as cost-quality
  points per tier, forming per-condition curves. Claims of superiority MUST cite matched
  tiers; cross-tier comparison is void.
- **Tolerance**: realized spend MUST land within the manifest-pinned tolerance of the tier
  (default ±10%); runs outside tolerance are excluded and re-run, and the exclusion logged.
- **Accounting classes**: participant tokens split into object-level (task reasoning,
  artifact production) vs. coordination overhead (workspace operations, projections,
  relay/synthesis scaffolding). Judge/scoring tokens are outside participant budgets.
  Overhead fraction is a first-class reported metric per run.
- Budget exhaustion mid-run follows each condition's own termination semantics (for C6,
  N14 §7 forced synthesis); the partial result is scored, not discarded.

## 5. Replication, comparability, provenance

- **Replication**: k ≥ 3 replicates per (condition, task, tier). The kernel MUST group by
  (experiment_id, label) and aggregate per cell: mean/min–max score, majority status,
  within-cell spread. A row is divergent only when stable across replicates.
- **Comparability preconditions**: `compare` MUST fail — rejecting the comparison and
  naming the offending snapshots — on: mixed `experiment_id`, mixed registry version,
  mixed product, duplicate `state_var_id` within a snapshot, or non-replicate label
  collisions. Axes declared as varying in the manifest are exempt. Implementations MAY
  additionally emit warnings, but a warning is never a substitute for the failure.
- **Provenance stamping**: every snapshot MUST carry `source_hashes` of task inputs, the
  policy/evaluator version, judge model id + rubric hash, and price-table hash. Uniformity
  within an experiment is a precondition unless the axis is declared as varying.
- **Absence is divergence**: a condition producing no evaluation for a state variable
  (crash, timeout, refusal) MUST surface as coverage divergence, scored as failure for that
  variable — not as a missing cell.

## 6. Task class

### 6.1 Requirements

A conforming task MUST have all of:

1. **Underdetermined specification**: ≥2 injected contradictions or ambiguities between
   stakeholder inputs, each with a sealed intended resolution.
2. **Multi-constraint acceptance**: ≥3 interacting constraints such that locally reasonable
   choices violate a distant constraint.
3. **Execution-verifiable success**: hidden acceptance tests runnable in a capsule.
4. **Evidence-gathering component**: at least one question answerable only by inspecting
   the provided repository/telemetry/fixtures, not by prior knowledge.
5. **Long horizon**: reference solution requires state exceeding a single context window at
   the task's lowest budget tier (this is where shared persistent state can matter at all).
6. **Decision-record deliverable**: the run must output what was decided, against which
   alternatives, and why.

### 6.2 Packaging and sealing

A task ships as: seeded repository, stakeholder inputs, telemetry/fixtures, hidden test
suite, coverage checklist, decision-record rubric, and the sealed resolutions. Participants
MUST NOT be able to read sealed material (harness-enforced via capsule environment). All
fixture content MUST be synthetic and identifiable as synthetic; no real names, quantified
outcomes styled as real evidence, or plausible-real identifiers. Tasks are versioned;
result comparisons across task versions are void.

## 7. Metrics and scoring

Per run, the snapshot MUST record these as state variables (thresholds pinned in the
registry entry):

- **success** — hidden acceptance tests (primary; execution-scored)
- **coverage** — requirement checklist fraction (judge-scored; judge model pinned and
  disjoint from participant models)
- **contradiction handling** — sealed-resolution match per injected contradiction (judge-scored)
- **constraint violations** — count, from tests + judge
- **decision-record quality** — rubric score (judge-scored)
- **cost**, **wall-clock**, **tokens by class**, **activation/attempt count**
- **overhead fraction** (§4)
- **hypothesis diversity** — count of live alternatives at budget midpoint (anti-anchoring
  signal; C4–C6 only)
- **failure category** — closed v1 set, judge-coded with human spot-check: specification
  misread, premature consensus, livelock/loop, lost context, verification skipped,
  coordination overhead exhaustion, other

## 8. Analysis and gates

### 8.1 Effect rule

An effect is claimed only when between-condition spread exceeds within-condition
(replicate) spread on the same (task, tier), consistently across replicates. Otherwise the
result is reported as null. The matrix MUST display within-cell spread next to
between-condition spread.

### 8.2 Ablation delta

For every ablation pair, delta = C6 − C7 per metric per (task, tier). The ablation delta is
the direct measurement of whether shared state is load-bearing: if C6 beats baselines but
delta ≈ 0, the win comes from population/roles/budget shape, not the workspace — and MUST
be reported as such.

### 8.3 Pre-registration

Exact thresholds (task count, replicate count, win margins, overhead ceilings) MUST be
pinned in the experiment manifest before runs execute. Manifests are content-addressed;
post-hoc threshold edits void the experiment.

### 8.4 Gate G0 — N14 Stage 0 → Stage 1 (build the substrate?)

Run C1, C2, C4, C6, C7 (C6 at N14 Stage 0) on ≥5 conforming tasks × ≥3 replicates × ≥2
tiers. Promote iff both:

1. C6 beats or ties the best of C1/C2/C4 on **success** at a matched tier on the majority
   of tasks, under §8.1, with no tier where C6 is dominated across the board; and
2. the ablation delta on **success** or **coverage** is positive under §8.1.

Fail → N14 Stage 1 is not built; C2/C3 remain available as ordinary workflows; the
negative result is recorded in the decision record.

### 8.5 Gate G1 — N14 Stage 1 → Stage 2

As G0, plus: C3 and C5 included; C6 MUST beat C5 (§3); overhead fraction MUST be under the
manifest ceiling; and C6's cost-quality curve MUST NOT be dominated by any baseline curve
across the tested tiers.

### 8.6 Regression gate

Once any condition is adopted as incumbent for a task class, subsequent architecture changes
run `diff` against the incumbent baseline; a change that worsens success or cost at matched
tier blocks adoption.

## 9. Reporting

Per experiment: the condition × state-variable matrix (aggregated cells with spread);
per-condition cost-quality curves; ablation deltas; failure-category distribution; gate
verdict with the manifest hash it was judged under. Reports are N6-style artifacts:
inputs, registry, and manifest pinned by digest.

## 10. Minimal compliant implementation (MCI)

An MCI MUST:

- run one experiment with C1, C2, C6, C7 on ≥2 conforming tasks, ≥3 replicates, ≥2 tiers
- enforce §5 comparability preconditions and replicate aggregation in `compare`
- produce the §9 report including overhead fraction, ablation delta, and a G0 verdict
- demonstrate absence-as-divergence with one induced crash run

---

**Version History:**

- 0.1.0-draft (2026-07-22): Initial collective-cognition evaluation harness specification
