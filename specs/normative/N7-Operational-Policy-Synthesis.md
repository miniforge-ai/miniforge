<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N7 — Operational Policy Synthesis With Verification

**Version:** 0.2.1-draft
**Date:** 2026-08-06
**Status:** Complete
**Conformance:** MUST
**Class:** Extension spec (N7+)

## 0. Status and scope

### 0.1 Purpose

This specification defines the normative requirements for **Operational Policy Synthesis**
**with Verification** (OPSV): a Fleet Mode capability that discovers scaling signals and
performance bottlenecks via governed experiments, synthesizes operational policies, verifies
them against explicit acceptance criteria, and emits fixes as auditable artifacts.

### 0.2 Relationship to core and later extensions

OPSV is an extension of existing Miniforge normative contracts:

- **N1**: introduces new *concepts* (Experiment Pack, Operational Policy, Actuation Mode,
          Verification) as specializations of Workflow/Policy Pack/Artifact/Evidence.
          Now landed in N1 §2.11–§2.14 and §13 (glossary).
- **N2**: supplies the phase lifecycle, gate, and bounded-loop contracts that the `opsv.*`
          workflow family specializes in this specification.
- **N3**: adds required event types for experiments and verification.
          Now landed in N3 §3.14 (OPSV Events).
- **N4**: defines new gates and policy-pack controls for experiment governance, safety, and actuation.
          Now landed in N4 §5.1.5 (OPSV Gates Pack).
- **N5**: defines Fleet Mode command surfaces and navigation primitives for experiments and policy diffs.
          Now landed in N5 §2.3.3 (OPSV Commands) and §3.2.6 (OPSV Drill-Down View).
- **N6**: defines evidence bundle requirements for experiment provenance, reproducibility, and
          verification artifacts. Now landed in N6 §2.8 (OPSV Evidence) and §3.1.1 (artifact types).
- **N8**: supplies emergency-stop and safe-mode behavior for active OPSV runs (§3.1.4, §3.4).
- **N10/Ariadne**: govern PR creation and direct apply as external effects using
  DecisionEnvelopes, ExecutionGrants, EffectTransactions, rollback verification,
  postconditions, audit events, and evidence.

### 0.3 Non-goals

OPSV SHALL NOT:

- require machine learning or reinforcement learning to be correct; deterministic and heuristic convergence is sufficient.
- attempt to fully replace platform-native autoscaling mechanisms; it synthesizes policies that target them.
- require production experiments; staging-only operation is compliant.

## 1. Terminology

### 1.1 Operational Policy

An **Operational Policy** is a versioned set of runtime configuration artifacts that control
service behavior under load, including at minimum:

- autoscaling configuration (replica scaling and/or event-driven scaling)
- resource sizing configuration (requests/limits recommendations or settings)
- runtime guardrails (rate limits, concurrency caps, circuit breaker settings) where applicable
- observability/alerting adjustments required to safely operate the policy

Operational Policy artifacts SHALL be emitted with provenance per N6.

### 1.2 Experiment Pack

An **Experiment Pack** is a versioned, declarative artifact that defines:

- workload model (traffic shape, mixes, datasets, warmup/cooldown)
- target environment(s) (cluster/namespace/service selectors)
- instrumentation requirements (metrics and traces that MUST be available)
- guardrails (abort criteria, blast radius limits, time windows)
- success criteria (SLO thresholds, stability criteria, cost ceilings)
- convergence strategy (search space, step sizes, stopping conditions)
- actuation intent (recommend-only, PR-only, or apply)

Experiment Packs SHALL be hash-addressed and recorded in the event stream and evidence bundle.

### 1.3 Verification

**Verification** is the process of executing an Experiment Pack (or a verification subset) against a candidate Operational
 Policy and producing an evidence bundle showing whether success criteria are satisfied.

### 1.4 Requested Actuation Mode

OPSV MUST support these requested actuation modes:

- **RECOMMEND_ONLY** (`:recommend-only`): produce policy proposals and evidence; no changes are emitted.
- **PR_ONLY** (`:pr-only`): produce changes as PRs (or patch sets) against declared repos.
- **APPLY_ALLOWED** (`:apply-allowed`): request direct apply eligibility; it does not grant execution authority.

`APPLY_ALLOWED` MUST be disabled by default.

A requested mode is intent, not permission. The effective actuation decision MUST be
derived under §5.4. PR creation and direct apply remain governed N10 effects and require
valid authority at execution time.

## 2. System model

### 2.1 Inputs

An OPSV run MUST accept:

1. **Service Target Set**: the set of services Fleet Mode has access to (via repo selectors and/or runtime selectors).
2. **Environment Target Set**: staging and/or production-canary targets.
3. **Domain Description** (optional but recommended): SLOs, dependency topology, key request types, known bottlenecks.
4. **Experiment Pack**: provided or generated.

### 2.2 Outputs

An OPSV run MUST produce:

- **Operational Policy Proposal** (one or more) including:
  - scaling signals selected and justification
  - target thresholds, min/max bounds, stabilization behaviors
  - resource sizing proposals
  - any required runtime guardrails
- **Verification Result** with explicit pass/fail and reason codes
- **Evidence Bundle** per N6, including:
  - Experiment Pack hash and content
  - environment fingerprint (cluster, node pool, image digests, config)
  - normalized risk score and explainable factor records
  - metric queries and snapshots used for conclusions
  - artifacts emitted and diffs
- **Remediation Artifacts** depending on actuation mode (none, PRs, or applied changes)

## 3. Workflow requirements

### 3.1 Workflow family

Miniforge SHALL define an `opsv` workflow family with at least these phases:

1. **DISCOVER**

- Identify candidate scaling drivers and bottleneck hypotheses.
- Validate that required instrumentation exists; if not, emit an instrumentation remediation plan.

1. **PLAN**

- Produce or refine an Experiment Pack.
- Compute a risk score (see §5) and required approvals.

1. **EXECUTE**

- Run the experiment(s) against baseline and/or candidate policies.
- Enforce guardrails with automatic abort and evidence capture.

1. **CONVERGE**

- Iterate load and/or configuration within declared search space.
- Stop based on explicit stopping conditions (see §3.4).

1. **SYNTHESIZE**

- Emit Operational Policy Proposal(s) with ranked confidence and tradeoffs.

1. **VERIFY**

- Execute verification suite (may be subset of full experiment pack) and produce pass/fail.

1. **ACTUATE**

- Emit PRs or apply changes (only if allowed), then schedule drift monitoring triggers.

### 3.2 Fleet Mode integration

Fleet Mode SHALL be able to:

- run OPSV workflows across a target set with bounded concurrency
- present a per-service “policy state” view (current vs proposed vs verified)
- triage and drill-down into evidence bundles and event streams per N5/N6

### 3.3 “Fix them” requirement

When verification fails or discovery identifies a bottleneck, OPSV MUST be capable of producing at least one of:

- a scaling signal change (driver selection and thresholds)
- resource sizing change (requests/limits recommendation or PR)
- guardrail change (concurrency caps, rate limits) where the system has explicit templates and safety policies
- instrumentation change required for correctness (metrics/tracing), emitted as a separate PR bundle

OPSV SHALL NOT emit code changes unless explicitly requested by workflow intent and permitted by policy packs.

### 3.4 Convergence loop requirements

CONVERGE MUST be expressed as a bounded loop with:

- declared parameters (load step size, config step size)
- explicit stop conditions:
  - success criteria satisfied with headroom
  - guardrail abort triggered
  - maximum iterations reached
  - confidence threshold reached (deterministic heuristic acceptable)
- stabilization rules to avoid “chasing noise”:
  - minimum measurement window per step
  - required repetitions for pass/fail if variance exceeds declared tolerance

## 4. Data contracts

### 4.1 Experiment Pack schema (normative fields)

An Experiment Pack MUST use the N1 §2.12 namespaced top-level keys:

```clojure
{:experiment-pack/id string
 :experiment-pack/version string
 :experiment-pack/targets {:services [...] :environments [...]}
 :experiment-pack/workload
 {:profile keyword
  :mix [...]
  :warmup-seconds long
  :cooldown-seconds long}
 :experiment-pack/success-criteria {...}
 :experiment-pack/guardrails {...}
 :experiment-pack/convergence {...}
 :experiment-pack/actuation-intent keyword
 :experiment-pack/required-instrumentation [...]}
```

Success criteria MUST cover latency/error windows and stability, with optional
cost/resource ceilings. Guardrails MUST cover abort thresholds, blast radius,
and time windows. Convergence MUST define the search space, iteration limit,
and stop conditions. Actuation intent MUST be `:recommend-only`, `:pr-only`, or
`:apply-allowed`.

### 4.2 Operational Policy Proposal schema (normative fields)

A proposal MUST use the N1 §2.11 namespaced top-level keys:

```clojure
{:operational-policy/id string
 :operational-policy/version string
 :operational-policy/target-services [string ...]
 :operational-policy/target-envs [string ...]
 :operational-policy/scaling {...}
 :operational-policy/resources {...}
 :operational-policy/guardrails {...}
 :operational-policy/verification-summary
 {:passed? boolean :confidence keyword :caveats [string ...]}
 :operational-policy/rollback-plan {...}
 :operational-policy/evidence-refs [uuid ...]}
```

Scaling MUST include chosen drivers, thresholds, behaviors, and min/max bounds.
Resources MUST contain requests/limits recommendations or settings. The rollback
plan MUST identify a concrete N10-governed rollback action.

### 4.3 Event stream additions (N3 extension)

OPSV SHALL emit these event types with required minimal payloads:

- `:opsv.experiment/planned` (Experiment Pack hash, targets, risk score)
- `:opsv.experiment/started` (Experiment Pack hash, environment fingerprint)
- `:opsv/load-step` (step id, intended load, observed load)
- `:opsv.guardrail/abort` (trigger, threshold, observed, rollback action)
- `:opsv.convergence/iteration` (iteration id, params, observed metrics summary)
- `:opsv.policy/proposed` (policy hash, diff artifact refs, confidence)
- `:opsv.verification/result` (pass/fail, criteria evaluation, confidence, caveats)
- `:opsv.actuation/emitted` (requested/effective mode and correlated N10 effect records)
- `:opsv.drift/detected` (signal, deviation, suggested re-run)

Every event MUST include `:opsv/evidence-bundle-id` for the preallocated OPSV
evidence bundle per N6 §2.8.

## 5. Governance and safety (N4 extension)

### 5.1 Risk scoring

OPSV MUST compute a risk score for each run using at least:

- environment class (staging vs prod-canary vs prod)
- blast radius configuration
- service criticality label (if available)
- whether actuation is requested

Risk score MUST determine required gates and approvals.

The risk result MUST contain a normalized score in `[0.0, 1.0]`, a level in
`:low`, `:medium`, `:high`, or `:critical`, and explainable factor records containing
the input, contribution, and rationale. Policy packs map score/level thresholds
to approvals; implementations MUST NOT hide approval selection in an opaque model.

### 5.2 Gates

Policy packs SHALL define gates for:

- **Instrumentation Gate**: required signals exist and are reliable.
- **Environment Gate**: targets are allowed and within time windows.
- **Blast Radius Gate**: max changes bounded.
- **Abort Gate**: abort triggers configured.
- **Actuation Gate**: requested vs effective mode; apply requires an explicit allowlist.
- **Evidence Completeness Gate**: evidence bundle contains required fields before actuation.

If any gate fails, OPSV MUST produce remediation guidance as machine-readable output and human-readable summary.

### 5.3 Default posture

- `APPLY_ALLOWED` MUST be disabled by default.
- Production targets MUST require explicit allowlisting in policy packs.
- All OPSV runs MUST support a global emergency stop.

An N8 emergency stop or safe-mode entry MUST prevent new OPSV effects, abort
active experiments at the next safe boundary, revoke their mutation grants,
invoke verified rollback through the separately authorized recovery path, and
record the disposition in N3/N6. Safe mode MUST set effective actuation to
`:none` per N8's A0 posture.

### 5.4 Effective actuation decision

Before any external mutation, OPSV MUST compute an effective actuation decision
from the requested mode, verification result, N4 gate results, N8 safe-mode
state, and current Ariadne ExecutionGrant. The decision MAY reduce autonomy but MUST NOT
promote beyond the requested mode.

- `:none` emits only disposition evidence and is required when N8 forbids execution.
- `:recommend-only` never produces an external mutation.
- `:pr-only` requires a matching active grant for the PR-creation effect.
- `:apply-allowed` requires successful verification, all gates, a valid scoped
  ExecutionGrant at execution time, a verified rollback, and configured postconditions.

The decision and every authority/effect reference MUST be recorded in N6 evidence.

## 6. Verification requirements

### 6.1 Verification suite

Each proposal MUST have a defined verification suite derived from the Experiment Pack, minimally:

- a ramp test sufficient to observe scaling response
- a steady-state window to evaluate SLO compliance
- a stability window to detect oscillation and pod churn

### 6.2 Pass/fail semantics

Verification MUST evaluate each success criterion and produce:

- per-criterion result
- overall pass/fail
- confidence/caveat fields (e.g., variance high, dependency noise)

Verification failure MUST block direct apply and MUST NOT be overridden by a
policy pack. `PR_ONLY` MAY emit a failed candidate for review only when the PR
is marked ineligible for merge and includes the failed criteria and evidence.

## 7. Emission and remediation

### 7.1 Artifact emission

All changes MUST be emitted as artifacts with provenance per N6:

- policy diffs
- experiment pack versions
- verification reports
- dashboard/query snapshots

### 7.2 PR emission (required)

`PR_ONLY` actuation MUST emit:

- at least one PR per target repo or a coordinated patch set
- included evidence bundle link/reference and rollback instructions in PR body
- Ariadne grant, decision-envelope, and effect-transaction references for the provider mutation

### 7.3 Apply emission (optional, gated)

`APPLY_ALLOWED` MAY apply changes directly only when §5.4 succeeds. Apply actions
MUST execute as N10-governed effects with verified rollback and postcondition
monitoring. A failed postcondition MUST trigger the declared rollback and record
both the apply and rollback outcomes as artifacts, events, and evidence.

## 8. CLI/TUI extensions (N5 extension)

Miniforge MUST implement the canonical N5 §2.3.3 commands under `fleet`:

- `fleet opsv plan …` → generate Experiment Packs and risk/gate status
- `fleet opsv run …` → execute and converge
- `fleet opsv verify …` → run verification suite
- `fleet opsv propose …` → emit policy proposals without actuation
- `fleet opsv emit …` → PR-only emission
- `fleet opsv apply …` → gated apply (if enabled)

The TUI SHALL provide drill-down:
Fleet → Service → OPSV Runs → (Experiment Pack, Events, Evidence, Policy Diff, Verification)

## 9. Minimal compliant implementation (MCI)

A minimal compliant OPSV implementation MUST:

- support staging-only operation
- execute all seven §3.1 phases through the shared N2 workflow lifecycle
- discover at least two candidate scaling drivers (e.g., CPU and a backlog/concurrency proxy if available)
- run step/ramp experiments with abort guardrails
- synthesize an HPA/KEDA-compatible policy proposal
- produce explainable risk and per-criterion verification results
- emit the §4.3 events and a complete N6 §2.8 evidence bundle
- emit PRs as N10-governed actions with provenance
- default effective actuation to `:recommend-only`
- honor N8 emergency stop and record rollback/disposition evidence

---

**Version History:**

- 0.2.1-draft (2026-08-06): Replaced stale N10 intent/OIR/capability
  correlation with the adopted Ariadne DecisionEnvelope, ExecutionGrant, and
  EffectTransaction contracts
- 0.2.0-draft (2026-08-04): Reconciled canonical schemas, events, evidence,
  requested/effective actuation, verification blocking, N8 safe mode, and N10 effects
- 0.1.0-draft (2026-02-01): Initial OPSV extension specification
