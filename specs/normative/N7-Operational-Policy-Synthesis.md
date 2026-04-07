<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N7 — Operational Policy Synthesis With Verification

## 0. Status and scope

### 0.1 Purpose

This specification defines the normative requirements for **Operational Policy Synthesis**
**with Verification** (OPSV): a Fleet Mode capability that discovers scaling signals and
performance bottlenecks via governed experiments, synthesizes operational policies, verifies
them against explicit acceptance criteria, and emits fixes as auditable artifacts.

### 0.2 Relationship to N1–N6

OPSV is an extension of existing Miniforge normative contracts:

- **N1**: introduces new *concepts* (Experiment Pack, Operational Policy, Actuation Mode,
          Verification) as specializations of Workflow/Policy Pack/Artifact/Evidence.
          Now landed in N1 §2.11–§2.14 and §12 (glossary).
- **N2**: defines a new workflow family (`opsv.*`) and convergence loops as instances of the
          validate→feedback→repair→re-validate pattern.
- **N3**: adds required event types for experiments and verification.
          Now landed in N3 §3.13 (OPSV Events).
- **N4**: defines new gates and policy-pack controls for experiment governance, safety, and actuation.
          Now landed in N4 §5.1.5 (OPSV Gates Pack).
- **N5**: defines Fleet Mode command surfaces and navigation primitives for experiments and policy diffs.
          Now landed in N5 §2.3.3 (OPSV Commands) and §3.2.6 (OPSV Drill-Down View).
- **N6**: defines evidence bundle requirements for experiment provenance, reproducibility, and
          verification artifacts. Now landed in N6 §2.8 (OPSV Evidence) and §3.1.1 (artifact types).

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

### 1.4 Actuation Mode

OPSV MUST support these actuation modes:

- **RECOMMEND_ONLY**: produce policy proposals and evidence; no changes are emitted.
- **PR_ONLY**: produce changes as PRs (or patch sets) against declared repos.
- **APPLY_ALLOWED**: apply changes directly only when explicitly permitted by policy packs and gates.

`APPLY_ALLOWED` MUST be disabled by default.

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

An Experiment Pack MUST include:

- `id`: stable identifier
- `version`: semantic version or monotonic revision
- `targets`:
  - `services`: selectors (repo and/or runtime)
  - `environments`: selectors (cluster/namespace)
- `workload`:
  - `profile`: step/ramp/spike definitions
  - `mix`: request classes and weights (if applicable)
  - `warmup_seconds`, `cooldown_seconds`
- `success_criteria`:
  - latency thresholds (p95/p99) and measurement window
  - error rate threshold and window
  - stability criteria (max oscillation, max pod churn)
  - optional cost ceiling or resource ceiling
- `guardrails`:
  - abort triggers with thresholds (error budget burn, saturation, tail latency)
  - blast radius limits (max replicas delta, max node delta, namespaces)
  - time windows
- `convergence`:
  - search space definition (candidate signals, thresholds, min/max)
  - iteration limits and stop conditions
- `actuation_intent`: one of `RECOMMEND_ONLY | PR_ONLY | APPLY_ALLOWED`
- `required_instrumentation`: list of metrics/traces/log signals that MUST be present

### 4.2 Operational Policy Proposal schema (normative fields)

A proposal MUST include:

- `policy_id`, `policy_version`
- `target_services`, `target_envs`
- `scaling`:
  - chosen driver(s)
  - thresholds and behaviors
  - min/max bounds
- `resources`:
  - requests/limits recommendation or settings
- `guardrails` (if emitted)
- `verification_summary`: pass/fail plus confidence score and known caveats
- `rollback_plan`: explicit rollback action
- `evidence_refs`: pointers to evidence bundle elements per N6

### 4.3 Event stream additions (N3 extension)

OPSV SHALL emit these event types with required minimal payloads:

- `opsv.experiment_planned` (pack hash, targets, risk score)
- `opsv.experiment_started` (pack hash, environment fingerprint)
- `opsv.load_step` (step id, intended load, observed load)
- `opsv.guardrail_abort` (trigger, threshold, observed, rollback action)
- `opsv.convergence_iteration` (iteration id, params, observed metrics summary)
- `opsv.policy_proposed` (policy hash, diff refs, confidence)
- `opsv.verification_result` (pass/fail, criteria evaluation)
- `opsv.actuation_emitted` (PR refs or apply refs)
- `opsv.drift_detected` (signal, deviation, suggested re-run)

All events MUST link to the corresponding evidence bundle id per N6.

## 5. Governance and safety (N4 extension)

### 5.1 Risk scoring

OPSV MUST compute a risk score for each run using at least:

- environment class (staging vs prod-canary vs prod)
- blast radius configuration
- service criticality label (if available)
- whether actuation is requested

Risk score MUST determine required gates and approvals.

### 5.2 Gates

Policy packs SHALL define gates for:

- **Instrumentation Gate**: required signals exist and are reliable.
- **Environment Gate**: targets are allowed and within time windows.
- **Blast Radius Gate**: max changes bounded.
- **Abort Gate**: abort triggers configured.
- **Actuation Gate**: PR-only vs apply allowed; apply requires explicit allowlist.
- **Evidence Completeness Gate**: evidence bundle contains required fields before actuation.

If any gate fails, OPSV MUST produce remediation guidance as machine-readable output and human-readable summary.

### 5.3 Default posture

- `APPLY_ALLOWED` MUST be disabled by default.
- Production targets MUST require explicit allowlisting in policy packs.
- All OPSV runs MUST support a global emergency stop.

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

Verification failure MUST block actuation unless a policy pack explicitly allows “force apply” with elevated approval gates.

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

### 7.3 Apply emission (optional, gated)

`APPLY_ALLOWED` MAY apply changes directly only when all gates pass and the policy pack
 allows it; apply actions MUST be recorded as artifacts and events.

## 8. CLI/TUI extensions (N5 extension)

Miniforge SHOULD add commands under `fleet` (exact naming may be refined, but contracts MUST exist):

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
- discover at least two candidate scaling drivers (e.g., CPU and a backlog/concurrency proxy if available)
- run step/ramp experiments with abort guardrails
- synthesize an HPA/KEDA-compatible policy proposal
- produce verification pass/fail and evidence bundle
- emit PRs with provenance
