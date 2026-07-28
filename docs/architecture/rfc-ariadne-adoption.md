<!--
  Title: RFC: Adopting Ariadne in Miniforge
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# RFC: Adopting Ariadne in Miniforge

**Status:** Proposed (2026-07-28). Graduated from the owner-reviewed
adoption assessment; the analysis below is unchanged from that
review apart from naming and the ratification list.

**Ariadne** is the tenancy/ownership/access architecture distilled
in [thesium-career's
`docs/architecture/tenancy-ownership-access.md`](https://github.com/miniforge-ai/thesium-career/blob/main/docs/architecture/tenancy-ownership-access.md)
(v1.5): clause-set policy labels, `decide()` decision envelopes,
execution grants with lineage, transacted effects, tenants and
Zanzibar-style relations, revocation-for-cause. It is
engine-agnostic by design; this RFC assesses its adoption for
miniforge against the as-built code (panels A1-A3), the N-specs and
Fleet specs (T1-T2), and the contradiction register (T3) in
[diagrams/](diagrams/).

## Decisions to ratify

Accepting this RFC ratifies three proposals; everything else below
is analysis in support of them.

1. **Adoption order** -- the four steps of "Suggested adoption
   order" below: envelopes + fail-closed `decide()` first, then
   grants on the irreversible effects, then tenants/owners on the
   run hierarchy, then Fleet re-scope.
2. **Fleet re-scope precedes any further Fleet governance build.**
   The five in-memory governance stubs and N12's signing half are
   deleted in favor of Ariadne primitives before more is built on
   them.
3. **N10 is subsumed, not extended.** Certification tiers are
   re-expressed as ExecutionGrants + attestation + breach-history
   routing eligibility; N10 and half of N12 get rewritten as
   profiles of Ariadne's six frozen interfaces.

Router placement (a miniforge function consumed by data planes) is
already decided in principle and is not re-opened here.

## TL;DR

Adoption is unusually **downhill**. Miniforge already built the
hard halves of this architecture without naming them: fail-closed
gates, evidence bundles, an intervention round-trip, a
data-vs-instruction trust model, advisory-never-blocking
coordination, and meta agents gating semantic intent. What Ariadne
adds is the *unifying representation* those pieces are missing —
and that representation directly dissolves **five of the nine
recorded contradictions**, deletes most of Fleet's unbuilt
governance scope before anyone builds it, and converts the
security story from "audited prose discipline" to "structural
impossibility." The main genuinely new builds are the clause/label
store, the `decide()` kernel, and grant objects — and N10 already
specifies the grant half under another name.

## Where miniforge stands (one paragraph)

The engine is real: specs in, governed PRs out, daily. Governance
exists as compiled policy packs driving fail-closed gates at phase
transitions, with evidence bundles and an append-only event
stream. Identity, however, is absent — no tenant, no principal, no
owner on any record — and authority is ambient: an agent that is
running can do what the process can do, bounded by budgets wired
per-domain (one miswired channel already produced a 3h40m runaway)
and by prose instructions to inner orchestrators. Fleet's
governance layer (RBAC, approvals, quotas, pack distribution,
certification) is specified and stubbed but not built. That
combination — strong execution governance, absent
identity/authority governance — is exactly the seam Ariadne fills.

## The mapping

| Ariadne primitive | miniforge counterpart today | disposition |
|---|---|---|
| Tenant / Principal / `#controller` | absent (T3's tenancy note) | **adopt** — operators, customer orgs, agent instances, service identities |
| Engagement | absent; closest: a Spec commissioned by someone | **adopt** — the commissioning relationship for runs on a repo/fleet |
| Owner on every record | absent; runs/checkpoints/events are install-scoped | **adopt** — execution artifacts → launching tenant; domain outputs → repo owner's tenant |
| `PolicyClause` (multi-authority, enforcement, relaxation) | pack rules + `:rule/enforcement` + three severity enums | **replace** — rules become clause emitters; one vocabulary |
| `DecisionEnvelope` from `decide()` | gate results + policy-eval entities (two shapes, two derivers) | **replace** — one envelope, runtime-signed, reason codes + obligations + revision pins |
| Transacted effects (propose→commit→reconcile) | phases produce artifacts; effectful ops (push, merge, deploy, spend) execute directly | **adopt** for effectful ops — the MoA blackboard direction |
| `ExecutionGrant`/`Delegation` (lineage, `:delegable?`, TTL) | ambient authority + per-domain budgets; N10 *specifies* capability grants, no ambient authority | **adopt** — N10 is subsumed, not extended |
| Revocation-for-cause + breach→eligibility | circuit-breaker FSM; redirect budgets (partially wired) | **adopt** — budgets/conditions on grants; breach history feeds routing |
| Scoped taint + label plane (model-unwritable) | `:authority/data` vs `:authority/instruction`, levels tainted/untrusted/trusted — data exists, enforcement is prose | **upgrade** — same trust model, enforced representation |
| Policy transforms (attested) | meta agents judge semantic intent; verdicts are agent output | **upgrade** — meta-agent verdicts become attested inputs to envelopes, not free text |
| Registry 4-split (definition/version/binding/grant) | tool-registry + llm backends + runtime registry.edn (flat) | **restructure** — models and tools become capability bindings |
| Policy-first routing | model selector (quality/cost) | **extend** — eligibility filter before ranking; fallback re-runs eligibility |
| Delivery ≠ acceptance | inbound PR comments/webhooks/issues consumed directly | **adopt** — porch custody for push intake |
| Takeaway export | n/a today; Fleet's forcing function is "customer controls all data" | **adopt** — org-tenant export makes the claim structural |

## What the contradictions become

| T3 # | today | under adoption |
|---|---|---|
| 1 — two derivers of supervisory truth | spec fight over who may derive | dissolved: envelopes are runtime-signed; consumers project freely but cannot mint truth |
| 2 — entity contract is convention | hand-written Rust mirror, silent drops | dissolved: versioned, content-addressed, attested contracts are the §13.6 registry discipline |
| 4 — governance fails open | vocabulary mismatch → silent pass | dissolved **by representation**: permission is clause intersection; unknown/missing policy fails closed (§13.7); "unknown gate passes" becomes unrepresentable |
| 5 — three severity vocabularies | three enums, none enforced | dissolved: one clause vocabulary; enforcement = relaxation mode (hard-halt = none; require-approval = workflow; warn/audit = envelope obligations) |
| 6 — nested orchestrators | restrain Claude Code via prose | reframed: stop restraining, start fencing — the inner orchestrator is a principal with lent grants; free inside them, gated at every boundary by no-ears `decide()` |
| 7 — WorktreeInbox dead-drop | measures writes, not reads | dissolved: decisions become transactions with unknown-outcome + reconciliation — an unacknowledged delivery is a first-class pending state |
| 9 — FSM doesn't own decisions / runaway burn | budget wired to one of two channels | bounded structurally: budgets are grant constraints checked at `decide()` and re-checked at commit; breach = revocation for cause; the runaway class ends at the grant ceiling regardless of wiring |
| 3, 8 (spec-text drift, spec surface) | editorial/process | not addressed by adoption — the conformance-manifest recommendation stands |

Five dissolved, two structurally bounded, two remain process work.

## What we delete (unbuilt work that never gets built)

The biggest win is subtraction. Fleet's governance half is
currently five in-memory stubs plus N12 — all of it a bespoke
parallel model. Under adoption:

- **`authz` (RBAC/roles/permissions)** → relations + grants. RBAC
  is expressible in ReBAC; the EDN role seeds become tuples.
- **Approvals FSM** → relaxation workflows on clauses (the Mary
  machinery: scopes, TTLs, grantor policy).
- **`resource-management` (org budgets/quotas)** → entitlement
  tuples + grant constraints.
- **`policy-distribution` (central pack registry/rollout)** → packs
  are org-owned assets shared with clauses riding the copies — the
  coach's-office handout, already designed. Drift/staleness =
  freshness contract territory.
- **Waivers** → scoped relaxation grants with expiry polarity.
- **Certification tiers (N10)** → trust as breach-history +
  attestation feeding routing eligibility — the crossing-off
  remembers.
- **N12 SSO/SCIM** → credential→principal mapping in the authn
  layer (thin); **signing/private registries** → capability-version
  attestation, already required by §13.6.

Fleet shrinks to what it is uniquely good at: **advisory
coordination** (registry, heartbeat, conflict directives) plus the
hosted relation/policy store. That is a fraction of the spec'd
build — and none of the deleted work has been built yet, which is
the cheapest possible time to delete it.

## What we simplify (built things that get simpler)

- **One enforcement pipeline.** Gates, meta-agent verdicts, budget
  checks, and Fleet approvals are today four mechanisms; they
  become one `decide()` with four kinds of input. The gate core
  (enter→gates→leave, fail-closed) survives as the kernel it
  already is.
- **One truth artifact.** Evidence bundles, policy-eval entities,
  and gate errors converge on the envelope + receipt pair; the
  console renders envelopes instead of mirroring entity schemas.
- **Budgets stop being plumbing.** Every budget becomes a grant
  constraint with one check-site; no more per-channel wiring to
  audit.
- **The isolation ladder gets a selector.** Worktree vs docker vs
  k8s stops being static config and becomes a function of effect
  class + clause destination constraints — plan-security hard-stops
  are already clause-shaped.
- **Contracts stop drifting.** One attested, versioned schema
  discipline covers supervisory entities, operator events, and
  envelopes.

## The security story, before and after

| surface | today | after |
|---|---|---|
| prompt injection | trust levels recorded; inner orchestrators restrained by prose | authority lives in grants injected text cannot write; the runtime has no NL input; bounded, not persuaded |
| forged results | an agent could emit text shaped like a passing verdict | label plane model-unwritable: verdicts and labels are runtime-computed/attested; a forged verdict is a picture of a verdict |
| governance bypass | fail-open bugs (vocab mismatch, unknown gates) | fail-closed by representation; missing policy metadata blocks the record |
| runaway compute | budget wiring bugs → hours of burn | grant ceilings at decide + commit; revocation for cause; breach degrades future granting |
| revocation | tuple-of-the-day (config change), effective eventually | authoritative at the store, effective at commit — fencing + freshness watermarks; Phase D commands inherit this |
| inbound content | webhooks/PR comments consumed directly | porch custody: delivered ≠ accepted; provenance names the hand and the hour |
| customer data (Fleet) | "customer controls all data" as deployment posture | structural: org tenant owns records; export/takeaway is a right; operators hold passes, not keys |
| multi-operator | explicitly deferred (N5) | free: a second operator is one more principal with grants |

## What stays untouched

The engine's identity is unthreatened: phase pipeline, DAG +
isolation executors, workflow FSM as the single phase authority,
checkpoints/harvest, event stream, connectors, the console's
observe path, minibench. Adoption is a layer *under* these (owners
on records, grants on actions) and a unification *across* them
(clauses, envelopes) — not a rewrite of any of them.

## Honest costs

- **New builds:** the clause store + label plumbing; the `decide()`
  kernel; grant/delegation objects with lineage; the transacted
  effect coordinator for effectful ops. (Router placement already
  decided in principle: a miniforge function consumed by data
  planes.)
- **Migrations:** pack rules → clause emitters (mechanical but
  broad); gate results → envelopes (the console rendering follows);
  effectful call sites → proposals (start with PR merge, deploy,
  spend — the irreversible three).
- **Spec debt:** N10 and half of N12 should be rewritten as
  profiles of the six frozen interfaces rather than amended — less
  text, not more.
- **The two process contradictions (3, 8) don't move** — the
  conformance manifest and spec-text cleanup remain their own work.

## Suggested adoption order

1. **Envelopes + fail-closed `decide()` around the existing gate
   core.** Smallest change, kills contradictions 4 and 5 — the two
   that undermine a governance product's credibility — and gives
   the console one truth artifact (kills 1 and 2 with it).
2. **Grants on the irreversible effects** (merge, deploy, spend)
   with budget constraints and revocation-for-cause. Bounds the
   runaway class (9); makes Phase D commands transacted.
3. **Tenants + owners on the run hierarchy**; contracts attested.
   Unlocks multi-operator and the Fleet data story.
4. **Fleet re-scope before any further Fleet build** — implement
   coordination + relation/policy store; delete the five governance
   stubs and rewrite N10/N12 as interface profiles. This honors the
   standing "finish N11 before Fleet expansion" recommendation:
   N11's capsule attestation *is* capability-version attestation.

Step 1 is deliberately first because it is the one the audits
already demanded for other reasons — adoption should start where
it pays for itself before anyone believes in the rest.

---

*Sources: as-built and target panels (miniforge #1548 + #1549
corrections, source-verified); the contradiction register (T3);
Ariadne v1.5 §§2–13; N-spec status per
ROADMAP and the 2026-06-10 design review; Fleet specs N10–N12;
governance audit 2026-07-05.*
