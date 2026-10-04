<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Shared deliberation: an experimental engineering team

**Decision:** retain shared, revisable reasoning state; repair its contracts before
production integration. **Profile:** synthetic development lab, not an adopted
workflow phase or an N15 G0 result. **Architecture basis:** Ariadne v1.6.1.

Open [the architecture atlas](index.html) for five selectable, full-size panels.
The editable source is [render_diagrams.py](render_diagrams.py); regenerate with
`python3 docs/architecture/deliberation/render_diagrams.py` from the repository root.
The SVGs use the same blue bands, colored responsibility zones and annotated
cards as the Ariadne atlas. They have no external dependencies.

| Panel | Question it answers |
|---|---|
| [1. Design evolution](diagrams/01-design-evolution.svg) | What changes from the original N14, and what remains a target? |
| [2. Work turn and effects](diagrams/02-work-turn-and-effects.svg) | Who may propose, commit, authorize and observe an outcome? |
| [3. Scoped information flow](diagrams/03-scoped-information-flow.svg) | When does one participant's information affect another participant or provider? |
| [4. Evidence and reconsideration](diagrams/04-evidence-and-reconsideration.svg) | How can experiments challenge an adopted conclusion without deleting history? |
| [5. Experiments and gates](diagrams/05-experiments-and-gates.svg) | What can run now, and what is still required for promotion? |

## The distinction that governs the design

A deliberation decision says **what the team chooses and why**. An Ariadne
decision envelope says **which exact action is authorized**. An effect receipt
says **what happened**. None substitutes for either of the others.

The board contains goals, claims, questions, alternatives, experiment proposals,
evidence references, plans, decisions, blockers and derived conflicts. A work
turn receives a projection and returns a proposal. The runtime stamps identity,
validates the whole candidate, commits one version atomically and returns feedback.

The runtime checks structure, source attestations and executable predicates. It
cannot determine whether arbitrary prose is true or whether two natural-language
claims contradict each other. Semantic conflict identification is a proposed
interpretation; an explicit contradiction edge makes the structural relation
available to deterministic scheduling and inspection.

## Changes from N14 v0.2

| Original assumption | Revised contract | Current implementation |
|---|---|---|
| Role name identifies an actor | Participant identity and responsibility are separate; runtime establishes identity | Admission stamps participant/activation/basis; lab uses synthetic participants |
| Every run needs seven agents | Manifest chooses population; responsibilities and required independence are explicit | Lab uses three peers, or one participant for its single control |
| Accepted conclusions are terminal | Challenge adoption without erasing it; supersede explicitly | Accepted decision retains its status plus a contested marker and challenge record |
| Challenge requires existing evidence | Also allow a concern plus a resolution criterion | `reason` + `resolution` is admitted, subject to a participant/object cap |
| Same operation and targets means duplicate | Compare the full operation payload per participant | Distinct claims and new evidence no longer collide as retries |
| Whole board is default context | Access, relevance and provider eligibility govern delivered inputs | Synthetic-only adapter; arbitrary-data policy remains a prerequisite |
| Hidden projection is enough for ablation | Control state and storage must not relay peer contributions either | Blind condition uses private boards and a fixed schedule |
| Object growth measures progress | Answers, refinements and resolutions are progress; repeated passes signal inactivity | Commits reset quiet count; a full round of passes is required |
| Closure gets an additional allowance | Reserve closure inside each condition's total allowance | Final lab turn is always metered; low-level loop can reserve one turn |
| In-memory graph is sufficient | Durable history precedes continuing execution; graph is reconstructible | Immutable hash-chained EDN journal and replay verification |

A revision stores the previous statement in object history. Supersession names a
replacement rather than deleting the earlier object. Explicit contradictions
remain live for adopted claims; withdrawing or superseding a participant resolves
the derived conflict while retaining the conflict record.

## Running development experiments

The standalone project avoids changing the canonical software-factory workflow.
From `projects/deliberation-lab`:

```sh
# Core regressions and lab integration tests.
clojure -M:test

# Offline mechanics: 60 cells, 510 mock activations, no model calls.
clojure -M:run smoke resources/deliberation-lab/development.edn /tmp/deliberation-smoke

# Verify the immutable chain and reconstruct every recorded board from transactions.
clojure -M:run verify /tmp/deliberation-smoke

# Real model calls, with the same manifest and a NEW output directory.
clojure -M:run run resources/deliberation-lab/development.edn /tmp/deliberation-live
```

Output parent directories must already exist; output directories must not exist.
A run never overwrites an earlier experiment. It writes an experiment-opened
record, durable invocation intents, activation outcomes with board snapshots,
cell results and a final report record. `report.md` is a derived convenience copy;
the journal is authoritative. An interrupted run is incomplete, not silently
resumable. A timed-out call is recorded with unknown usage and is not retried.

The live adapter requires the installed `claude` CLI and its normal authentication.
It uses an explicit model id, fresh sessions, empty working directories, no tools,
no MCP servers, no hooks, no project settings and no session persistence. It sends
only synthetic task views. Provider usage comes from the CLI envelope; model text
cannot supply accounting. Invalid output consumes an activation and its recorded
usage. No fallback model is selected silently.

The manifest pins the population policy, tiers, replicates, model and normalized
price weights. Before invocation the runner additionally hashes the prompt, task
package and implementation resources. Weights are an accounting convention, not
a claim about current vendor prices. Results include exact cell identity, observed
usage, normalized cost, wall time, failures, final solutions and rationales.

**Budget limitation:** turn counts are enforced and each call has a timeout and
provider dollar guard. Normalized spend is checked between calls; a single call
can cross that ceiling, is recorded as such and prevents a successful result.
The CLI's dollar guard is not proof of a strict single-call cost bound. A hard,
pre-reserved provider budget and verified token limits are prerequisites for N15
budget conformance. A timeout can leave provider spend unsettled.

## What is being compared

| Development condition | State and communication |
|---|---|
| `single` | One participant repeatedly works over its own typed state; not a conforming continuous-session N15 C1 |
| `independent` | Fresh private candidate boards; metered final selector sees submitted solutions and rationales |
| `relay` | Private participant boards plus a sequential handoff of solutions and rationales |
| `workspace` | One shared typed graph; fixed proposer/skeptic/synthesizer schedule |
| `workspace-ablated` | Same schedule and budget, but separate participant graphs and no peer handoff |

The final synthesis/selection turn is included in every allowance. The blind
condition does not share graph versions, goal updates, feedback, scheduler
triggers or artifact lineage. The model view excludes runtime sequence numbers
and activation identities in every condition. There is no arbitrary workspace
query or filesystem access in this profile.

The two fixtures are explicitly synthetic: payment retry semantics and a
permission-cache design. A participant may inspect only each fixture's named
observations. The trusted reader creates receipt-backed execution evidence; a
model cannot invent an observation by supplying an evidence class. Sealed expected
choices are not included in the request. Reference choices pass, and supplied
distractors fail, the deterministic scoring rules. These short tasks are useful
for plumbing and exploratory behavior; they are **not calibrated long-horizon
N15 tasks** and do not produce production code artifacts.

Mock solutions deliberately use the reference choices to exercise plumbing. A
mock success has no evidential value about model quality or shared-state benefit.
The development report shows success counts, normalized spend and paired
shared-minus-blind deltas. Its G0 verdict is always **not evaluated**. Neither
significance, calibrated uncertainty nor causal architectural superiority is
claimed. Failure remains a scored cell rather than disappearing from the matrix.

## Ariadne integration boundary

The existing tenancy, grant, envelope and effect-transaction components are the
integration points. The lab does not add its own tenant model, permission store,
policy label system, approvals FSM or external-effect executor. Its synthetic
participant ids are not a replacement for runtime-established agent principals.

Before non-synthetic data can enter:

1. Establish acting context, principal and delegation at the real workflow boundary.
2. Stamp object ownership, policy references and provenance before publication.
3. Authorize object selection and provider destination before sending a projection;
   compute output clauses from delivered inputs outside the model channel.
4. Apply the same checks to queries, evidence reads and provider fallbacks. A
   required constraint that cannot be disclosed requires an authorized reformulation
   or refusal, not silent omission.
5. Submit external effects through existing Ariadne effect transactions, including
   exact scope, approval binding, current grants, fencing and reconciliation.
6. Register N14's event and evidence types in N3/N6 before production emission.

The full clause-set store, destination resolver and trusted transformation plane
are not supplied by the current `policy-clause` severity vocabulary or the
phase-gating `decide()` function alone. The architecture panels deliberately mark
that general policy behavior as a target rather than painting the synthetic
adapter as a complete security implementation.

## Promotion beyond development

Before claiming G0, implement the full N15 controls and allowance semantics; add
calibrated, long-horizon seeded repository tasks with sealed executable acceptance
suites and decision-record evaluation; measure coordination overhead; pin complete
model populations, scoring versions and provenance; and preregister paired
success effects, practical margins and uncertainty methods. Failed, refused,
missing and timed-out outcomes remain failures, with unknown spend distinguished.

A negative result rejects the tested protocol, model population, task class and
budget regime. Retain that record and revise or demote that protocol version;
it does not establish that every future shared-state architecture is ineffective.
