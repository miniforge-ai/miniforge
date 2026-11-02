<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N14 — Shared Deliberation Workspace

**Version:** 0.3.0-draft
**Date:** 2026-11-02
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
- **Gate G0 passes.** Status remains Draft but is no longer speculative; Stage 1 conformance
  applies as written.
- **Gate G0 fails.** This specification MUST be re-statused **Informative** and annotated
  as a recorded negative result — retained to state what was tested and why it should not
  be built, with the deciding N15 experiment report (manifest hash) attached. It MUST NOT
  be deleted; the record is the deliverable.

### 0.5 Ariadne alignment and experimental profile

Ariadne v1.6.1 §§11 and 13, cited at its release tag `ariadne/v1.6.1`
([tenancy-ownership-access][ariadne-v1.6.1]),
is the authority and information-flow contract for this specification. Adopting a
later Ariadne version is a spec change here, not an implicit drift.
A deliberation decision records a chosen alternative; it MUST NOT confer an
ExecutionGrant or substitute for a DecisionEnvelope or an effect receipt.
Participant identity, responsibility, activation identity and basis MUST be
established by the runtime, not accepted from model output.

A synthetic development profile — runnable experimental infrastructure over
synthetic tasks — is neither Stage 1 adoption nor N15 gate evidence. It MUST
NOT accept arbitrary repository or customer data while the Ariadne object-label
and provider-egress boundaries are absent.

[ariadne-v1.6.1]: https://github.com/miniforge-ai/miniforge/blob/ariadne/v1.6.1/docs/architecture/ariadne/tenancy-ownership-access.md

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
at creation, runtime-established participant identity, and links to related objects
(typed edges: `supports`, `contradicts`, `depends-on`, `resolves`, `discriminates`,
`supersedes`).

### 2.2 No content payloads

Objects MUST NOT embed file contents, diffs, or transcripts. Code and documents live in
capsules and evidence bundles; the workspace holds statements about them plus references
(§8). Statement text SHOULD be bounded (manifest-configurable cap per object).

### 2.3 Status model (structural, not probabilistic)

Objects carry a status from a closed set. Adoption is a scoped working choice, not proof
of truth:

- goals: `:open` → `:accepted` | `:rejected` (only via `close-goal`, which MUST reference
  the decision(s) covering the outcome)
- claims/hypotheses/plans/decisions: `:open` → `:contested` → `:accepted` | `:rejected` | `:superseded`
- questions: `:open` → `:answered` | `:retired`
- experiments: `:proposed` → `:running` → `:completed` | `:aborted`

An adopted decision MAY receive a new actionable challenge. Its accepted record
MUST remain identifiable; open dissent is recorded separately. A replacement MUST
explicitly supersede the prior decision before adoption at the same choice point.
Refinements MUST preserve prior statement history. `:accepted` MUST NOT prevent
recording contrary evidence or challenging the assumptions of a working choice.

Status transitions MUST be governed by structural rules, not numeric confidence:

- An open claim is `:contested` while unresolved challenges reference it. An adopted
  record retains its disposition and records a separate contested marker.
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
  Only `:execution` and `:user` evidence satisfy §2.3 acceptance rule (a). These classes
  MUST be derived from runtime receipts, not model assertions.

### 2.5 Derived conflicts

The workspace engine MUST derive `conflict` objects automatically when: two `:open`,
`:contested` or `:accepted` claims are linked `contradicts`; an artifact-ref or plan violates a `:hard`
constraint; or two `:open`, `:contested` or `:accepted` decisions target the same
choice point. Rejected and superseded decisions are retained history and derive no new
conflict. Conflicts MUST NOT be closed by
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
`declare-blocked`, `close-goal`, `supersede`.

`supersede` targets the replaced claim, hypothesis, plan, or decision and MUST name
exactly one `:replacement`: a distinct, non-terminal object of the same type that
already exists or is created earlier in the same transaction. The engine marks the
target `:superseded` and records the replacement on it. Superseding a decision
requires synthesizer responsibility.

### 3.3 Operation classes and merge semantics

| Class | Operations | Concurrency behavior |
|---|---|---|
| additive | assert-claim, add-question, attach-evidence, propose-*, register-artifact, challenge, declare-blocked | Commute; commit even on stale basis if touched objects still exist and are non-terminal |
| mergeable | refine-claim, revise-plan, answer-question | Commit if touched objects unchanged since basis; otherwise reject with current state returned |
| exclusive | accept-decision, reject-decision, record-experiment-result, merge-hypotheses, invalidate-artifact, close-goal, add-goal, add-constraint, retire-question, supersede | Commit only against current version of touched objects; single writer wins |

The additive rule's non-terminal requirement has one exception: `challenge` and
`attach-evidence` MAY target an `:accepted` object (§2.3). The target keeps its
`:accepted` disposition; a challenge records the separate contested marker.

### 3.4 Validation pipeline

The engine MUST validate, in order: schema conformance; role permission (§5.3); basis
staleness per §3.3; `:hard` constraint guards (§2.4); anti-livelock rules (§3.5); and
idempotency (an identical full operation payload already committed by the same participant
MUST be rejected; new payloads against the same targets are distinct contributions).
Rejections MUST be returned to the scheduler with a machine-readable reason and MUST be
logged as events (§9.1). A rejected transaction is discarded; the activation is not
retried automatically.

Every operation MUST enforce its creation and target types, required fields and
reference integrity. The runtime MUST reject model-supplied authority, policy or
unattested provenance metadata. Goal closure MUST name an adopted decision that
covers that goal. Candidate validation MUST account for earlier operations in the
same transaction without publishing partial state. Unadopted alternative decisions MAY
coexist at one choice point. Choice-point uniqueness binds adoption: admitting
`accept-decision` MUST reject a decision whose choice point already has another
`:accepted` decision, so agents cannot bypass it by creating different decision ids.

### 3.5 Anti-livelock rules

- A `challenge` MUST attach evidence, propose a discriminating experiment (in the same
  transaction), or name a specific concern (`:reason`) and a resolution criterion
  (`:resolution`). Bare objections MUST be rejected; repeated actionable objections
  remain bounded by the cap below. Uncertainty does not require an already completed
  experiment.
- Per (participant, object), at most N challenges MAY be open simultaneously (default N=2,
  manifest-configurable).
- A decision with open challenges MAY still be accepted when its closing rule fires (§7);
  unresolved challenges are recorded as dissent, not discarded.

## 4. Projections

### 4.1 Determinism and the only-input rule

A projection MUST be a deterministic function of (workspace version, runtime-established
participant identity, role configuration, the participant's authorized read scope,
projection parameters). Given identical inputs, byte-identical output MUST result.

A projection MUST first satisfy Ariadne read, purpose and destination constraints.
Relevance selection and N12 shedding MUST NOT broaden access. Output clauses MUST
be computed by the runtime from delivered inputs. Required goals or constraints
that cannot be disclosed require authorized reformulation or refusal.

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

A conforming implementation MUST support `cross_visibility: none`. Under it, the visible
set for a participant is exactly: objects authored by that participant (by runtime
participant identity, not by shared responsibility), objects authored by the
interpreter (the specification-derived set), objects injected via OCI (source `:user`),
and all goals and `:hard` constraints. §4.2's required content is then computed over the
visible set only: derived objects (conflicts) appear iff every object they reference is in
the visible set, and the delta covers visible objects only. No content, summary, or
existence signal of any object outside the visible set may appear in the projection. This
switch exists solely so N15 can measure whether shared state is load-bearing. It MUST NOT
alter scheduling, budgets, or validation.

## 5. Activations and roles

### 5.1 Statelessness

Each activation MUST be a fresh agent session (BYOM CLI agent per N1 agent binding). No
private memory MAY persist between activations of the same role (v1). An activation
produces at most one transaction, plus capsule artifacts where applicable.

### 5.2 Role configuration

A role is configuration, not code: `{agent binding, static prompt, projection parameters,
eligible event types, permitted operations, capsule policy}`. A run manifest MUST declare
its participant population, responsibility assignments, and required independence.
Responsibilities MAY be combined or assigned to multiple participants; there is
no mandatory seven-agent population. A responsibility is not a principal or an
authority grant. The following responsibilities describe the available work:

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
- Only **synthesizer** MAY `propose-decision`, `accept-decision`, `close-goal`, and
  `supersede` a decision; acceptance
  of decisions classified high-impact by policy pack (N4) MUST route to OCI approval (§10.2).
- Only **interpreter** MAY `add-goal`, and only the interpreter MAY `add-constraint` a
  `:hard` constraint (source `:spec`); goals and `:hard` constraints otherwise only via
  OCI. Any responsibility MAY `add-constraint` a `:soft` constraint (§2.4).
- Only **meta-watchdog** MAY `retire-question`; the user MAY retire questions via OCI.
- All roles MAY `assert-claim`, `add-question`, `declare-blocked`.

## 6. Scheduling (v0: structural)

### 6.1 Eligibility and selection

Committed transactions and derived conflicts produce events; a static eligibility table maps
event types to roles. The v0 scheduler MUST be deterministic: priority order (1) open
conflicts, (2) blocked goals, (3) stale open questions, (4) round-robin among eligible
roles; bounded concurrency (manifest-configurable); at most one in-flight activation per
role. Staleness is deterministic: an open question is stale when at least S committed
transactions have occurred since it was last touched — created, answered, or referenced
by any committed operation — with S pinned in the run manifest.

### 6.2 Prohibitions and the seam

The v0 scheduler MUST NOT consume model-generated numeric estimates (confidence, expected
information gain, self-reported priority). The scheduler MUST sit behind a declared
interface selectable per run manifest, so alternative policies (including learned ones) are
a benchmark axis under N15, not a rewrite.

### 6.3 Budgets

A run manifest MUST declare budgets: per-run and per-goal activation counts, cost ceiling,
and wall-clock ceiling. The manifest MUST additionally reserve a **closure allowance**,
inside the total condition allowance, sized for one synthesizer activation on every
declared dimension: activations, cost and wall-clock. Ordinary work on each dimension is
bounded by its ceiling minus that dimension's reserve, so no ceiling is exhausted before
closure. The scheduler MUST NOT start an ordinary activation that a budget forbids; the
forced-synthesis closure activation (§7) draws only from the reserved closure allowance
and MUST be bounded by what remains of every ceiling. A closure that the remaining
allowance cannot fund is recorded as unfunded rather than run past a ceiling.
Every scheduling decision (start, skip, budget-block) MUST be logged as an event with reason.

## 7. Termination

A deliberation run MUST close on the first of:

1. **Success** — all goals accepted with decision coverage. All goals terminal with at least
   one rejected goal is a distinct completed-with-rejected-goals outcome, not task success.
2. **Budget boundary** — any run-level budget's ordinary allowance (its ceiling minus the
   closure reserve, §6.3) exhausted. The engine MUST then run one final
   synthesizer activation ("forced synthesis") producing the best-available decision record
   with explicit dissent and open items. This activation is funded by the reserved closure
   allowance (§6.3), reserved before ordinary work and never added to the matched
   allowance, so the §6.3 prohibition and this requirement never conflict. Only a reserve
   too small to fund it leaves the closure recorded as unfunded (§6.3).
3. **Quiescence** — every participant scheduled for ordinary work passes in a full
   bounded round without a committed contribution, and no eligible work remains.
   Participants eligible only on deadlock or budget events (the meta-watchdog) are not
   part of that round; the deadlock rule still activates them before closure. Answering a question,
   revising a statement or resolving a conflict counts as progress even if the object
   count does not grow.
4. **Deadlock** — no eligible activations exist for any open goal. The meta-watchdog MUST be
   activated once before deadlock closure.
5. **OCI stop** (N8).

Closure with open challenges records them as dissent on the affected decisions
(`:contested-at-close`). Dissent MUST appear in the exported decision record.

## 8. Artifacts and capsules

- Implementer and verifier activations MUST run in N11 capsules. The capsule bootstrap
  lineage is the currently adopted artifact chain — the `artifact-ref` targeted by the
  latest `:accepted` adoption decision (defined below) — or the task baseline if none.
- Artifacts enter the workspace only as `artifact-ref` objects: capsule id, export digest,
  path list, statement of what changed. Never content (§2.2).
- Parallel implementer activations produce parallel capsules. Reconciliation is an
  **adoption decision**: a `decision` object whose subject is lineage selection, carrying
  exactly one `resolves` edge to the chosen `artifact-ref` and `supersedes` edges to each
  rejected alternative. It is created via `propose-decision` and committed via
  `accept-decision` (exclusive class, §3.3). The engine MUST NOT merge artifact contents.
  Non-adopted artifacts remain referenced (they are evidence of alternatives considered).

## 9. Events and evidence

### 9.1 Event types (N3 envelope)

`workspace/opened`, `workspace/transaction-committed`, `workspace/transaction-rejected`,
`workspace/activation-started`, `workspace/activation-completed`,
`workspace/conflict-derived`, `workspace/schedule-decision`, `workspace/budget-exceeded`,
`workspace/deadlock-detected`, `workspace/closed`.

**These proposed types are not yet registered in N3 §6.** Under N3 §6 an implementation MUST
NOT emit an unregistered `:event/type`, so this list cannot be read as an
emission requirement today. Registering them — a schema in N3 §3, a registry
row in N3 §6, and an emission point in N3 §4.1, together per N3 §6.1 — is a
prerequisite to implementing this section.

The list is retained as the proposed content of that amendment. It is stated
here rather than in N3 because this spec is speculative (§0.4) and N3's
registry is the contract every consumer reads; adding ten unimplemented types
to it would misrepresent the stream's surface.

`workspace/transaction-committed` payloads carry the full operation list; the event stream
is the workspace log (single source of truth; the materialized graph is derived state and
MUST be reconstructible from events).

### 9.2 Closure exports (N6)

On close, the engine exports four records: (a) the decision record — per-goal
outcome, chosen alternatives, rationale links, dissent; (b) the full transaction log;
(c) the final graph snapshot; (d) per-run accounting — activations, cost, tokens split into
object-level vs. workspace-overhead classes (consumed by N15).

None of these is an N6 §3.1.1 artifact type today, so as with §9.1 the export
is gated on registering them there. N6 owns the evidence schema; this spec
names the content, not a new evidence model.

## 10. Governance

### 10.1 Transactions as governed actions (Ariadne)

Transaction submission MUST cross a runtime-governed boundary under Ariadne's
authority, information-flow and effect planes. Responsibility checks are additional
structural constraints, not credentials. Policy packs (N4) MAY restrict adoption
or require an approval envelope. A board decision MUST NOT itself authorize an
external effect. Such effects use the existing grant/envelope/effect-transaction
protocol, including current authority, exact scope and reconciliation. N10 is a
profile of that architecture, not a parallel source of ambient permission.

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

## 14. Conformance Requirements

This spec is speculative (§0.4). These IDs bind an experimental implementation
before the N15 gate; if that gate fails, the spec demotes to informative and
these requirements withdraw with it.

| ID | Level | Requirement |
|----|-------|-------------|
| N14.WS.1 | MUST | Make the event stream the workspace log, with the graph derived and reconstructible from it (§9.1). |
| N14.WS.2 | MUST | Apply transactions atomically — a rejected transaction leaves no partial state (§3). |
| N14.WS.3 | MUST | Derive conflicts rather than letting a writer declare its own resolution (§4). |
| N14.WS.4 | MUST | Enforce the activation budget and terminate per §7 rather than running unbounded. |
| N14.WS.5 | MUST | Detect deadlock and terminate rather than stalling silently (§7). |
| N14.WS.6 | MUST NOT | Emit an unregistered event type — §9.1's list requires an N3 §6.1 amendment first. |
| N14.WS.7 | MUST | Establish participant, responsibility, activation and basis in the runtime, never from model output (§0.5). |
| N14.WS.8 | MUST NOT | Treat an adopted decision as authority for an external effect (§0.5, §10.1). |

### 14.1 Test Obligations

1. Replaying the transaction log reconstructs the graph exactly.
2. A rejected transaction leaves the graph byte-identical to its prior state.
3. A run exceeding its budget terminates with the §7 outcome, not by hanging.
4. A proposal that names a different responsibility, or carries authority or provenance
   fields, is rejected with no state change.

---

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**, recording implementation state as of 2026-11-02.

### A.1 Implemented experimental infrastructure

`components/deliberation-workspace` holds the Stage 0 core: the typed object graph,
the operation vocabulary, §3.4 concurrency validation, §3.5 guards, projections,
the v0 scheduler, closing rules, and the run loop. The 0.3.0 amendments
(participant identity, actionable challenges of adopted records, `supersede`,
semantic admission, closure inside the total allowance) are not yet reflected in it.

### A.2 Not implemented

None of §9.1's event types is registered in N3 §6, so none is emitted. N6 exports,
full Ariadne ownership, labels and provider policy, governed capsule execution, cost
reservation, wall-clock budgeting, and watchdog and OCI integration are absent. Nothing
here is an adopted workflow or a production authority boundary; §0.4 keeps this pre-gate.

---

**Version History:**

- 0.3.0-draft (2026-11-02): Ariadne alignment; participant identity separate from
  responsibility; revisable decisions and actionable challenges; `supersede`; semantic
  admission; closure inside the total allowance; synthetic development profile status.

- 0.1.0-draft (2026-07-22): Initial shared deliberation workspace specification
