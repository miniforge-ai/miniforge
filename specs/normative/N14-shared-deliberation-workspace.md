<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N14 — Shared Deliberation Workspace

**Version:** 0.1.0-draft
**Date:** 2026-07-22
**Status:** Draft (Speculative — see §0.4)
**Conformance:** MUST, scoped per §0.4
**Class:** Extension spec (N7+)

---

## 0. Status and Scope

### 0.1 Purpose

This specification defines a **Shared Deliberation Workspace**: a typed, event-sourced,
transactional reasoning state over which multiple agents operate concurrently. It is the
coordination substrate for multi-agent runs whose goal is joint problem construction —
not routing, voting, or answer fusion.

The workspace replaces transcript-passing between agents with **object mutation**: agents
read a projection of shared state and propose transactions against it. Every committed
transaction changes what every other agent can subsequently perceive.

### 0.2 Relationship to N1–N13

- **N1**: adds concepts (Workspace, Workspace Object, Transaction, Projection, Activation,
  Role, Closing Rule, Discriminating Experiment).
- **N2**: defines a new execution mode (**deliberation run**) that MAY run standalone or as
  a phase within a workflow DAG. N2 DAG semantics are unchanged; a deliberation run is a
  leaf from N2's perspective.
- **N3**: adds required workspace event types (§9). All workspace state changes ride the
  N3 envelope; the workspace log is an N3 event stream, not a parallel mechanism.
- **N6**: workspace closure exports evidence artifacts (§9.2); experiment results are N6
  evidence bundles.
- **N8 (OCI)**: the user steers deliberation via OCI control actions (§10.2).
- **N10 (Governed Tool Execution)**: transaction submission is a governed tool action (§10.1).
- **N11 (Task Capsule Isolation)**: activations that produce or execute artifacts run in
  capsules; artifacts enter the workspace by reference only (§8).
- **N12 (Agent Context Economy)**: projections are N12-conformant context payloads (§4.3).

### 0.3 Non-goals (v1)

- **Latent-state communication** (hidden states, KV-cache exchange). The BYOM population is
  closed-weight CLI agents; the channel is unavailable, and integrity/verification of latent
  payloads is unresolved. Research direction, not a contract.
- **Cross-run persistent workspaces.** A workspace is per-run. Cross-run knowledge belongs
  to the memory substrate.
- **Learned or model-estimated scheduling** (expected-information-gain bidding). v0
  scheduling is structural (§6). The scheduler is a pluggable seam so learned policies can
  be benchmarked later under N15.
- **Numeric confidence calibration.** Objects carry structural status, not probabilities (§2.3).
- **Direct agent-to-agent messaging.** All inter-agent influence flows through the workspace.

### 0.4 Lifecycle and demotion rule

This is a speculative specification: it contracts a capability whose value is unproven.
Its lifecycle is bound to the N15 gates:

- **Pre-gate (current).** Conformance language binds only experimental implementations —
  the Stage 0 pilot and anything else built to produce N15 evidence. This scoping is not a
  loophole: gate results are interpretable only if the pilot implements §4.1, §4.4, and
  §5.1 faithfully. An unfaithful pilot cannot falsify the design.
- **Gate G0 passes.** Status advances to Draft on the normative track; Stage 1 conformance
  applies as written.
- **Gate G0 fails.** This specification MUST be re-statused **Informative** and annotated
  as a recorded negative result — retained to state what was tested and why it should not
  be built, with the deciding N15 experiment report (manifest hash) attached. It MUST NOT
  be deleted; the record is the deliverable.

## 1. Terminology

- **Workspace**: the append-only transaction log plus its materialized typed object graph,
  scoped to one deliberation run.
- **Workspace Object**: a typed node (goal, constraint, question, claim, hypothesis,
  experiment, evidence, plan, decision, artifact-ref, conflict, blocker).
- **Transaction**: an agent-proposed set of operations against a stated basis version.
- **Projection**: the deterministic, role-specific rendering of workspace state that is an
  activation's task context.
- **Activation**: one stateless agent invocation — fresh session, projection in, at most one
  transaction out.
- **Role**: configuration binding an agent (BYOM), projection parameters, eligible events,
  and permitted operations.
- **Discriminating Experiment**: an experiment whose recorded outcome differs between two or
  more competing hypotheses or contested claims.
- **Closing Rule**: a structural condition under which a goal, decision, or run reaches a
  terminal state.

## 2. Workspace model

### 2.1 Object types

A conforming workspace MUST support these object types and MUST reject others (closed set, v1):

| Type | Purpose |
|---|---|
| `goal` | What the run must achieve; carries success criteria |
| `constraint` | A restriction; `:hard` or `:soft` (§2.4) |
| `question` | An open unknown, with priority |
| `claim` | An assertion; kind `:fact`, `:inference`, `:assumption`, or `:prediction` |
| `hypothesis` | A candidate explanation/design with explicit alternatives |
| `experiment` | A procedure that can produce evidence; MAY discriminate hypotheses/claims |
| `evidence` | Reference to an N6 evidence bundle or user/retrieval source |
| `plan` | Ordered intended work, linked to goals |
| `decision` | A committed choice, with rationale, alternatives, and dissent |
| `artifact-ref` | Reference (never content) to a capsule export: digest + paths (§8) |
| `conflict` | Derived object marking contradictory claims or constraint violations |
| `blocker` | Declared inability to proceed, with cause |

Every object MUST carry: stable id, type, authoring role, activation id, workspace version
at creation, and links to related objects (typed edges: `supports`, `contradicts`,
`depends-on`, `resolves`, `discriminates`, `supersedes`).

### 2.2 No content payloads

Objects MUST NOT embed file contents, diffs, or transcripts. Code and documents live in
capsules and evidence bundles; the workspace holds statements about them plus references
(§8). Statement text SHOULD be bounded (manifest-configurable cap per object).

### 2.3 Status model (structural, not probabilistic)

Objects carry a status from a closed set:

- goals: `:open` → `:accepted` | `:rejected` (only via `close-goal`, which MUST reference
  the decision(s) covering the outcome)
- claims/hypotheses/plans/decisions: `:open` → `:contested` → `:accepted` | `:rejected` | `:superseded`
- questions: `:open` → `:answered` | `:retired`
- experiments: `:proposed` → `:running` → `:completed` | `:aborted`

Status transitions MUST be governed by structural rules, not numeric confidence:

- A claim is `:contested` while any unresolved challenge references it.
- A claim MAY become `:accepted` only when (a) it has at least one evidence link of source
  class `:execution` or `:user` and no open challenge, or (b) a decision explicitly accepts it.
- Objects MUST NOT carry required numeric confidence fields. An optional free-form
  qualifier MAY be recorded; the scheduler and validators MUST NOT consume it (v1).

### 2.4 Constraint and evidence provenance

- `:hard` constraints MUST originate from the task specification or the user (via OCI).
  Exactly one role — the interpreter — MAY create `:hard` constraints, with source `:spec`
  and each traceable to the task specification (§5.3); the user creates them via OCI. No
  agent transaction may modify or retire a `:hard` constraint, and no role other than the
  interpreter may create one.
- Agents MAY propose `:soft` constraints.
- Evidence carries a source class: `:execution` (produced by a verifier-role activation via
  governed tools, referencing an N6 bundle), `:retrieval`, `:user`, or `:agent-analysis`.
  Only `:execution` and `:user` evidence satisfy §2.3 acceptance rule (a).

### 2.5 Derived conflicts

The workspace engine MUST derive `conflict` objects automatically when: two `:open` or
`:accepted` claims are linked `contradicts`; an artifact-ref or plan violates a `:hard`
constraint; or two decisions target the same choice point. Conflicts MUST NOT be closed by
deletion — only by a transaction that resolves or supersedes a participant.

## 3. Transactions

### 3.1 Shape

A transaction MUST carry: proposing role, activation id, basis version (the workspace
version the projection was rendered from), and an ordered list of operations from the
closed vocabulary (§3.2). Every operation MUST identify the objects it touches.

### 3.2 Operation vocabulary (closed set, v1)

`assert-claim`, `refine-claim`, `challenge`, `attach-evidence`, `add-question`,
`answer-question`, `retire-question`, `propose-hypothesis`, `split-hypothesis`,
`merge-hypotheses`, `propose-experiment`, `record-experiment-result`, `propose-plan`,
`revise-plan`, `propose-decision`, `accept-decision`, `reject-decision`,
`register-artifact`, `invalidate-artifact`, `add-goal`, `add-constraint`,
`declare-blocked`, `close-goal`.

### 3.3 Operation classes and merge semantics

| Class | Operations | Concurrency behavior |
|---|---|---|
| additive | assert-claim, add-question, attach-evidence, propose-* , register-artifact, challenge, declare-blocked | Commute; commit even on stale basis if touched objects still exist and are non-terminal |
| mergeable | refine-claim, revise-plan, answer-question | Commit if touched objects unchanged since basis; otherwise reject with current state returned |
| exclusive | accept-decision, reject-decision, record-experiment-result, merge-hypotheses, invalidate-artifact, close-goal, add-goal, add-constraint, retire-question | Commit only against current version of touched objects; single writer wins |

### 3.4 Validation pipeline

The engine MUST validate, in order: schema conformance; role permission (§5.3); basis
staleness per §3.3; `:hard` constraint guards (§2.4); anti-livelock rules (§3.5); and
idempotency (an operation identical to one already committed by the same role against the
same object MUST be rejected). Rejections MUST be returned to the scheduler with a machine-
readable reason and MUST be logged as events (§9.1). A rejected transaction is discarded;
the activation is not retried automatically.

### 3.5 Anti-livelock rules

- A `challenge` MUST attach either an evidence reference or a `propose-experiment` operation
  (in the same transaction) whose experiment discriminates the challenged object. Bare
  objections MUST be rejected.
- Per (role, object), at most N challenges MAY be open simultaneously (default N=2,
  manifest-configurable).
- A decision with open challenges MAY still be accepted when its closing rule fires (§7);
  unresolved challenges are recorded as dissent, not discarded.

## 4. Projections

### 4.1 Determinism and the only-input rule

A projection MUST be a deterministic function of (workspace version, role configuration,
projection parameters). Given identical inputs, byte-identical output MUST result.

The projection — plus the role's static prompt and, where applicable, the activation's
capsule contents (§8) — MUST be the activation's only task-specific input. Activations
MUST NOT receive other agents' transcripts, prior activation transcripts, or any channel
outside the workspace. This is the attribution guarantee: if shared state matters, it is
measurable by ablation (§4.4), because it is the only path by which influence travels.

### 4.2 Required content

Every projection MUST include: all goals; all `:hard` constraints; open conflicts touching
objects the role is eligible to act on; and the delta (object ids + summaries) since the
role's previous activation. Role-relevant selection of remaining objects is governed by
projection parameters.

### 4.3 N12 conformance

Projections are N12 context payloads: pre-flight measurement MUST run; on overflow the shed
ladder applies in this order — prose renderings of resolved objects, then terminal-status
objects, then graph-distant objects, then foreign rationale text. Goals, `:hard`
constraints, and conflicts assigned to the activation MUST survive all shed stages.
Activations SHOULD have access to a read-only workspace query surface (N12 §6 pattern) to
fetch objects outside the projection by id.

### 4.4 Ablation switch (normative)

A conforming implementation MUST support `cross_visibility: none`: projections then include
only objects authored by the receiving role plus goals, constraints, and specification-
derived objects. This switch exists solely so N15 can measure whether shared state is
load-bearing. It MUST NOT alter scheduling, budgets, or validation.

## 5. Activations and roles

### 5.1 Statelessness

Each activation MUST be a fresh agent session (BYOM CLI agent per N1 agent binding). No
private memory MAY persist between activations of the same role (v1). An activation
produces at most one transaction, plus capsule artifacts where applicable.

### 5.2 Role configuration

A role is configuration, not code: `{agent binding, static prompt, projection parameters,
eligible event types, permitted operations, capsule policy}`. A run manifest MUST declare
its role population. The seven-role minimum below applies to Stage 1 and later (§11);
Stage 0 pilots require only proposer, skeptic, and synthesizer. The minimum population
for a conforming Stage 1+ deliberation run:

- **interpreter** — task spec → goals, `:hard` constraints (from spec), questions
- **proposer** — hypotheses, plans, soft constraints
- **implementer** — artifacts in capsules, artifact-ref registration
- **skeptic** — challenges (bound by §3.5), conflict surfacing
- **verifier** — executes experiments via governed tools; sole producer of `:execution` evidence
- **synthesizer** — decisions, dissent recording, goal closure proposals
- **meta-watchdog** — activated only on deadlock/budget events (§7); may retire questions,
  propose scope reductions, or propose stopping

### 5.3 Operation permission matrix (v1 minimum)

- Only **verifier** MAY `record-experiment-result` and attach `:execution` evidence.
- Only **synthesizer** MAY `propose-decision`, `accept-decision`, `close-goal`; acceptance
  of decisions classified high-impact by policy pack (N4) MUST route to OCI approval (§10.2).
- Only **interpreter** MAY `add-goal` and `add-constraint` with source `:spec`; goals and
  `:hard` constraints otherwise only via OCI.
- Only **meta-watchdog** MAY `retire-question`; the user MAY retire questions via OCI.
- All roles MAY `assert-claim`, `add-question`, `declare-blocked`.

## 6. Scheduling (v0: structural)

### 6.1 Eligibility and selection

Committed transactions and derived conflicts produce events; a static eligibility table maps
event types to roles. The v0 scheduler MUST be deterministic: priority order (1) open
conflicts, (2) blocked goals, (3) stale open questions, (4) round-robin among eligible
roles; bounded concurrency (manifest-configurable); at most one in-flight activation per
role.

### 6.2 Prohibitions and the seam

The v0 scheduler MUST NOT consume model-generated numeric estimates (confidence, expected
information gain, self-reported priority). The scheduler MUST sit behind a declared
interface selectable per run manifest, so alternative policies (including learned ones) are
a benchmark axis under N15, not a rewrite.

### 6.3 Budgets

A run manifest MUST declare budgets: per-run and per-goal activation counts, cost ceiling,
and wall-clock ceiling. The scheduler MUST NOT start an activation that a budget forbids.
Every scheduling decision (start, skip, budget-block) MUST be logged as an event with reason.

## 7. Termination

A deliberation run MUST close on the first of:

1. **Success** — all goals terminal (`:accepted`/`:rejected` with decision coverage).
2. **Budget boundary** — any run-level budget exhausted. The engine MUST then run one final
   synthesizer activation ("forced synthesis") producing the best-available decision record
   with explicit dissent and open items.
3. **Quiescence** — k consecutive committed transactions produce zero net new open objects
   (default k=3), and no eligible activations remain that could change that.
4. **Deadlock** — no eligible activations exist for any open goal. The meta-watchdog MUST be
   activated once before deadlock closure.
5. **OCI stop** (N8).

Closure with open challenges records them as dissent on the affected decisions
(`:contested-at-close`). Dissent MUST appear in the exported decision record.

## 8. Artifacts and capsules

- Implementer and verifier activations MUST run in N11 capsules. The capsule bootstrap
  lineage is the currently adopted artifact chain (the latest `:accepted`
  artifact-adoption decision), or the task baseline if none.
- Artifacts enter the workspace only as `artifact-ref` objects: capsule id, export digest,
  path list, statement of what changed. Never content (§2.2).
- Parallel implementer activations produce parallel capsules. Reconciliation is an
  **adoption decision** (exclusive op) selecting one lineage; the engine MUST NOT merge
  artifact contents. Non-adopted artifacts remain referenced (they are evidence of
  alternatives considered).

## 9. Events and evidence

### 9.1 Required event types (N3 envelope)

`workspace/opened`, `workspace/transaction-committed`, `workspace/transaction-rejected`,
`workspace/activation-started`, `workspace/activation-completed`,
`workspace/conflict-derived`, `workspace/schedule-decision`, `workspace/budget-exceeded`,
`workspace/deadlock-detected`, `workspace/closed`.

`workspace/transaction-committed` payloads carry the full operation list; the event stream
is the workspace log (single source of truth; the materialized graph is derived state and
MUST be reconstructible from events).

### 9.2 Closure exports (N6)

On close, the engine MUST export as N6 artifacts: (a) the decision record — per-goal
outcome, chosen alternatives, rationale links, dissent; (b) the full transaction log;
(c) the final graph snapshot; (d) per-run accounting — activations, cost, tokens split into
object-level vs. workspace-overhead classes (consumed by N15).

## 10. Governance

### 10.1 Transactions as governed tool actions (N10)

Transaction submission MUST be exposed to agents as a governed tool. Operational intent
classification applies; policy packs (N4) MAY gate operation classes (e.g., forbid
`accept-decision` autonomy in a given environment).

### 10.2 OCI surfaces (N8)

A conforming implementation MUST expose via OCI: workspace inspection (graph + log);
user-injected operations (add `:hard` constraint, add question, challenge, accept/reject
decision); pause/resume; budget adjustment; stop. User operations carry source `:user` and
are subject to N8 audit, not §5.3 role restrictions.

## 11. Conformance staging

- **Stage 0 — file-backed pilot.** Workspace as versioned EDN/JSON files in a repository;
  static round-robin scheduler; ≥3 roles (proposer, skeptic, synthesizer minimum);
  validation MAY be script-enforced rather than engine-enforced; §4.4 ablation switch MUST
  work. Purpose: run N15 Gate G0 cheaply.
- **Stage 1 — engine substrate.** Full §2–§5, §8–§9: typed objects, validated transactions,
  deterministic projections, capsule binding, N3 events, closure exports.
- **Stage 2 — event-driven operation.** Full §6–§7 and §10: eligibility scheduling, budgets,
  meta-watchdog, OCI surfaces.

Promotion between stages MUST be gated on N15 experiment outcomes (N15 §8). If Gate G0
fails, Stage 1 is not built; baseline architectures (N15 §3, C1–C5) remain available as
ordinary N2 workflows, and this specification demotes per §0.4.

## 12. Minimal compliant implementation (MCI)

A Stage-1 MCI MUST:

- open a workspace from a task specification (interpreter seeding goals/constraints)
- run ≥3 roles as stateless activations over deterministic projections
- validate and commit transactions per §3, rejecting a stale exclusive write and a bare challenge
- derive at least one conflict and resolve it via a discriminating experiment with
  `:execution` evidence
- close on a §7 rule and export the §9.2 artifacts
- support `cross_visibility: none`

## 13. Rationale (non-normative)

**Statuses, not confidences.** Model-emitted probabilities are uncalibrated; a scheduler or
acceptance rule consuming them becomes unfalsifiable knob-turning. Structural rules
(evidence-linked status transitions) are auditable and testable. Calibration can be added
later as a measured, versioned layer if N15 shows the lack is binding.

**Stateless activations.** If agents carry private memory, the workspace can degrade into
decoration — a record of deliberation that happened elsewhere. Making the projection the
only input makes shared state causally load-bearing and makes the ablation switch a real
experiment rather than a plausibility argument.

**No direct messaging.** A second channel would defeat attribution, replay, and audit. The
cost — everything serializes through typed objects — is deliberate and is measured (overhead
fraction, N15) rather than assumed away.

**Structural v0 scheduling.** Salience/information-gain scheduling is a second research
problem stacked on the first. It ships as a benchmarkable alternative behind the scheduler
seam, not as a v1 dependency.

**Prior art.** Blackboard systems (Hearsay-II, BB1) demonstrated both the pattern and its
historical failure mode — control. This spec's answer is deterministic v0 scheduling,
explicit budgets, and closing rules, with control-policy search deferred to N15. Recent
LLM-era results (blackboard-style shared workspaces, global-workspace event loops, latent
collaboration) inform the design but are not incorporated as contracts; the harness exists
to test their claims under matched budgets before adoption.

---

**Version History:**

- 0.1.0-draft (2026-07-22): Initial shared deliberation workspace specification
