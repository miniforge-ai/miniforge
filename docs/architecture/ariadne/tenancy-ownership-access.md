# Ariadne — Tenancy, Ownership, and Access

**Name.** Ariadne gave Theseus the thread that let him walk into
the labyrinth and get back out. This architecture is that thread:
provenance and authority traceable in both directions, and a
guaranteed way out — revocation, takeaway export, leaving day.
The name pairs with the Theseus engine (Thesium's), where the
model was first distilled; the architecture itself is
engine-agnostic and portable.

**Version:** 1.6.0 — tagged `ariadne/v1.6.0`. Cite the tag, not
`main`: see [VERSIONING.md](VERSIONING.md) for what a major/minor/patch
bump means and how to fork against a fixed version.

**Status:** v1.5 lineage (2026-07-27; semantic-convergence pass — §11
rewritten to §13 mechanics, authority-defined policy
transformations, clause destination-constraints with expiry
polarity and relaxation modes, purpose-basis, first-class
delegations, decision envelopes, freshness contract, scoped taint),
distilled from ADR 008 and its implementation review (both in the
Thesium Career repository, where this architecture was first written;
that ADR is the product's own instantiation record, not part of the
portable architecture). Generic by intent: the model is written to be instantiated in
any product where people and organizations both hold data. Lineage:
first implemented for Ixi; ADR 008 adds the Zanzibar relation layer
and higher-fidelity personal ↔ org ↔ corporate interaction rules.

**Intended reuse:** interview articulation, an RFC for a corporate
codebase, adoption in other Miniforge products. §8 carries the
defense material; §11 extends the model to
agent orchestration and tool egress; §12 to third-party rights —
licensed feeds and open-web intake; §13 is the executable contract
the runtime enforces.

**Diagrams** (full-fidelity visual companion, one legend across all
five, in [diagrams/](diagrams/)):

1. [Core model](diagrams/01-core-model.svg) — tenants, principals,
   relations; containment vs relation; the four layers; axioms.
2. [Request path](diagrams/02-request-path.svg) — resolvers, the
   invocation context, the four-gate gauntlet, the check seam.
3. [Ownership by provenance](diagrams/03-ownership-provenance.svg) —
   intake channels, the IP boundary, projections, shared assets.
4. [Agent orchestration & tool egress](diagrams/04-agent-tool-gates.svg)
   — delegation, the three tool gates, the disclosure lattice,
   elevation and scoped grants.
5. [Revocation & third-party rights](diagrams/05-revocation-rights.svg)
   — one revocation mechanism across three domains, license routing,
   the takeaway export.

**Explained simply** (two stories, one universe — together they are
the simplicity invariant: a change that cannot be told in the story
is suspect):

- [Your House, Their Clubhouse, and the Passes](explainers/tenancy-for-a-nine-year-old.md)
  — §1–§9, §12: tenants, membership, provenance, the four layers,
  licenses, offboarding.
- [The Robot Helpers and the Sticker Rules](explainers/routing-for-a-nine-year-old.md)
  — §11, §13: agents, clauses, the doorkeeper, routing.

---

## 1. The problem this solves

Most systems start with one identity: a `user` string (or id) that is
simultaneously *who is operating*, *who the data is about*, and *how
rows are keyed*. That conflation is invisible while every operator is
also the subject of their own data. It becomes structural debt the
moment any of these appear:

- an operator who is not the subject (an admin, a manager, a coach, a
  teacher, a support engineer);
- an organization that shares assets with its members (a rubric, a
  catalog, a workspace) while members hold data that is *theirs*;
- offboarding, where the question "what leaves with the person and
  what stays with the org" must have a structural answer, not a
  policy document;
- hosting, where "who may read this row" needs an enforceable answer.

The usual industry answer is org-centric: the workspace owns
everything inside it. That answer fails any product whose value
proposition includes *the person's data outlives the membership* —
and it is unavailable entirely in bottom-up adoption, where no org
entity exists yet.

This architecture gives ownership, access, and portability a single
model that serves a single-person local deployment and a multi-org
hosted deployment without branching, and that can be adopted
incrementally in a system that has the one-string shape today.

## 2. Core model — four concepts

```text
Tenant     — the unit of ownership. Two kinds, structurally identical:
             personal        exactly one person
             organizational  a company / school / team-of-record
Principal  — the stable security actor: who is operating.
             Credentials and tokens are artifacts that AUTHENTICATE
             a principal; they are not the principal. Binds to
             tenants via #controller relations (usually one person,
             one personal tenant); may hold relations into other
             tenants (admin, manager, coach).
Workspace  — a context within an organizational tenant (team, program,
             cycle). A single default until org features need more.
Engagement — a scoped org↔person process object (an employment, a
             team membership, a review cycle). Org-granted roles
             attach to the engagement, never to the person's whole
             tenant (§4); revoking it revokes everything derived
             through it.
Relation   — a Zanzibar-style tuple linking principals and tenants to
             objects: membership, employment, visibility, commission.
```

Three axioms carry most of the weight:

**A1. People are tenants.** There is no separate "subject" or "user"
identity layer. The person a record is about is a personal tenant,
and `subject-id` *is* a personal-tenant id. This collapses what would
otherwise be a fourth identity dimension into the ownership dimension
that already exists.

**A2. Every record has exactly one owner tenant.** One owner slot, no
dual owner/subject stamping. Anything a second party may see is a
granted relation, not a second owner. Ownership answers "which
tenant controls this record's lifecycle, namespace, grants, and
default portability" (§6); it never encodes "who may see it."

**A3. Membership is a relation, never containment.** A person's
tenant is *related to* an organization's tenant
(`tenant:org#member@tenant:person`); it is never nested inside it.
Joining is writing one tuple; leaving is deleting it. No data moves
in either direction. Containment would make the org transitively hold
personal data — offboarding becomes data migration, and
the-org-owns-your-data returns by structure rather than by decision.

## 3. Four orthogonal layers

The design's discipline is keeping four questions in four layers that
never leak into each other's schema:

| layer | question | mechanism |
|---|---|---|
| Authentication | who is this principal? | resolver-selected per channel and tier: dev/test/trusted-internal self-resolved; shipped paid identity-backed; free per product (§5) |
| Authorization | may this principal do this to this object? | `check(principal, relation, object)` over relation tuples |
| Disclosure & processing | what does this record permit — to whom, for what purpose? | per-record policy clauses (audience × exportability × purpose, each carrying its imposing authority — §13.1), evaluated as a record-side veto inside the access check |
| Entitlement | may this tenant use this feature? | licensing tuples attached to the paying tenant |

Two of these are commonly conflated and must not be:

- **Disclosure is not access control.** A record marked confidential
  says what the record permits regardless of who asks; a relation
  tuple says who may ask. A manager relation does not read a
  confidential record; a public record inside the wrong boundary is
  still unreachable without a relation path. Modeling tenancy as
  visibility tiers on a confidentiality enum fails exactly here.
- **Entitlement is not access.** "May this tenant run this compute"
  and "may this principal see this record" are different questions
  with different answers. Fixed rule: **entitlement gates compute,
  never data** — losing a license never locks reading or exporting
  what your tenant owns. That is a property right of the owner, not a
  feature.

## 4. Authorization: the Zanzibar layer

Access is answered only by `check(principal, relation, object)` over
tuples of the form `object#relation@subject`, where the subject may
be a principal, a tenant, or a userset (`object#relation`).
Representative vocabulary:

```text
record:E#owner@tenant:alice                  ; materialized from the owner column
tenant:alice#controller@principal:alice      ; identity claims the tenant (§5)
tenant:acme#employee@tenant:alice            ; employment — the "nesting" relation, as a tuple
engagement:alice-acme#organization@tenant:acme ; the engagement's org side
engagement:alice-acme#subject@tenant:alice   ; the employment scoped as an object
engagement:alice-acme#manager@tenant:dana    ; org-granted role — over the ENGAGEMENT,
                                             ; never over alice's whole tenant
assessment:A#engagement@engagement:alice-acme ; commissioned records hang off
                                             ; the engagement, in the graph
assessment:A#subject@tenant:alice            ; a two-party record
asset:R#viewer@tenant:acme#employee          ; userset: all employees may read
license:L#holder@tenant:dana                 ; entitlement is tuple-shaped too
license:L#seat@tenant:alice
```

Org-granted roles attach to an **engagement object** (an employment,
a team, a review cycle), not to the person's tenant. A tenant-wide
`#manager` would expose self-serve records, other employers'
engagements, and private imports; scoping through the engagement
bounds access to what the engagement commissions — and because
commissioned records link to the engagement in the graph
(`assessment:A#engagement@…`), that bound is graph semantics, not
prose. Revoking the engagement revokes everything derived through
it. Person-granted relations (a coach the person hires themselves)
may legitimately be tenant-wide — the owner is granting over their
own property.

**Person-held roles bind to the person's tenant, resolved through
its controllers** (`engagement:alice-acme#manager@tenant:dana`,
exercised by any principal in `tenant:dana#controller` — so Dana
changing IdP or device does not orphan the role). Direct principal relations are for
credential-scoped actors: agents, service accounts, workload and
device identities, where the principal *is* the right grain.

Design consequences:

- **Role facts live in tuples, not on a context or a session.** There
  is no `roles: [admin]` set threaded through calls; a role is a
  relation somebody granted and somebody can revoke.
- **The checker is a seam.** Locally it is an in-process function
  over the same tuple vocabulary; in the single-tenant case every
  check resolves through `#owner` and is trivially true. When hosting
  begins, a Zanzibar implementation (SpiceDB, OpenFGA) replaces the
  function behind the same `check` signature. Tuple storage, rewrite
  rules, and consistency tokens (zookies) are that system's concern —
  they never enter the domain schema.
- **Negative tests precede the second principal.** A principal with
  no relation path to a record must be refused by scoped store APIs
  even while no second principal exists in any real deployment. That
  is the cheapest moment those tests will ever be written.

## 5. The invocation context

Every entry point (CLI, API handler) resolves one context and threads
it to every store and pipeline call:

```text
InvocationContext
  tenant      — the operating tenant (org or personal)
  workspace   — a default until org features need more
  principal   — who is operating
  subject     — personal-tenant id of the person the operation is about
  purpose     — processing purpose (service-delivery, analysis, …),
                evaluated against clause allowed-purposes (§13.1)
  purpose-basis — what roots the purpose in trusted authority: the
                grant, engagement, or workflow version that
                established it. Purpose is set by the trusted
                resolver or workflow runtime, never asserted by the
                model; a child agent may narrow it, never widen it
  run?        — optional explicit run scope
  revisions   — relation-revision and policy-revision pins, stamped
                by the runtime for freshness checks (§13.5)
```

Person-centric products bind one `subject`; the general form is a
subject *scope* — zero or several subjects — without changing
anything else here.

Rules:

- **Resolution is deployment-shaped; the context is not.** A local
  single-operator deployment resolves tenant = subject = the one
  personal tenant, workspace = default, with no login and no
  ceremony — a pure function of the request plus local state. A
  hosted deployment resolves the same fields from authentication.
  Downstream code cannot tell the difference and never branches.
- **Store APIs take the context, not naked ids.**
  `list(ctx)`, `save(ctx, record)` — internally keyed by the owner
  tenant resolved from the context, with every read/write routed
  through `check`. Call sites stop threading a bare user-id
  positional argument, which is the mechanical change that retires
  the one-string identity.
- **No derived-identity string conventions.** Patterns like
  `"{user}--{run_id}"` synthesizing a scoped identity by
  concatenation are deleted, not migrated; scoping is an explicit
  context field.
- **One identifier shape, one validator.** A single shared component
  defines the legal id shape for every store that embeds an id in a
  path, filename, row, or hash — before typed ids are introduced.
  Divergent per-store validation (reject here, sanitize there) is a
  real observed failure: the same id passes one store and fails
  another.

**Authentication posture is channel-and-tier policy, not
architecture.** "Self-authorized local" is a *resolver selection*,
not a property of the product. The context shape never varies; the
resolver that establishes `principal` does:

```text
dev / test / trusted-internal builds → self-resolved: the local
                                       operator, no ceremony
shipped free tier                    → decided per product:
                                       self-resolved OR identity-backed
shipped paid / pro / corp tiers      → identity-backed, ALWAYS —
                                       platform identity (App Store)
                                       or the product's own IdP (DMG),
                                       whatever the channel
```

Three consequences, one demand:

- **Identity-backed ≠ hosted.** Binding the principal to a durable
  identity moves nothing off-box. The quadrant (self-resolved vs
  identity-backed) × (local vs hosted store) has four valid cells;
  a shipped local-first product occupies identity + local, and any
  off-box confidentiality claim survives authentication untouched.
- **What identity buys**, in existing machinery: entitlement tuples
  anchored to a durable tenant (store receipts / subscriptions map
  to `license:` tuples), multi-device as one tenant with many
  installs, and tenant recovery off dead hardware — complementing,
  not replacing, the takeaway export.
- **The free-tier fork is reversible** precisely because the slot
  is reserved: a free tier shipped self-resolved can adopt identity
  later without a schema change.
- **The demand: tenant ids never derive from identities.** The
  identity maps to the principal in the authentication layer; the
  principal maps to the tenant; the tenant id stays stable
  underneath both. A self-resolved free-tier tenant upgraded later
  is *claimed* by the new identity — bound, never re-keyed. Derive
  the tenant id from the platform's user id and the upgrade re-keys
  every content address that embeds it (the §9 M3 landmine, again).
  Mechanically, claiming writes a validated `#controller` relation
  (`tenant:alice#controller@principal:alice`, as in §4; an
  additional device or IdP principal gets its own controller
  tuple); a tenant can
  exist with zero current controllers (pre-claim, recovery,
  deceased-subject custody), and several identities can control one
  tenant (multiple IdPs, devices). The same-human-two-devices case
  needs an explicit link/merge policy — never a silent auto-merge.

## 6. Ownership defaults: provenance, not type

The subtle rule set. First, what "owner" means here:
`owner_tenant_id` is **system control and custody** — the tenant
that controls the record's lifecycle, namespace, access grants, and
default portability. It is not a universal legal conclusion; legal
and policy interests ride separately as relations and clauses
(subject, commissioner, rights holder, restriction authority — §12,
§13.1). With that narrowed: **a record's type never implies its
owner kind** — the creating context decides, which is why the owner
is a column on the record rather than a rule per type. The defaults:

| record class | owner |
|---|---|
| intake / raw material | the **intake channel's tenant** — org-bound connectors land org-owned; personal import lands personal-owned |
| derived identity — what the system synthesizes *about a person* | the person's tenant, **always**; disclosure inherited from sources |
| self-serve outputs the person runs for themselves | the person's tenant |
| shared assets — rubrics, catalogs, templates, workspaces, connector bindings | the **importing tenant**, whichever kind it is |
| commissioned outputs — work run under an org's process about a person | the person's tenant **by default**; the commissioning tenant holds `#commissioner` / `#viewer` relations; org ownership only by explicit, named org-level policy |

Three rules resolve where ownership and IP claims intersect:

**Provenance rule.** Raw intake is not one thing: material arriving
through company channels carries the employer's IP claim; material
the person brings carries none. The route in already has an owner —
channels/connectors are tenant-owned assets — so intake takes the
channel's tenant. No per-record adjudication on the normal
ingestion path. Corrections (a mis-bound connector, a mistaken
import, a tenant merge) exist as a privileged, audited process that
recomputes policy and provenance afterward — rare by design, never
a routine knob. Where no org tenant exists, every channel is
personal and there is nothing to claim.

**Delivery is not acceptance.** A channel a tenant deliberately
leaves open to outside senders (a drop folder, inbound mail) is
pushable by anyone — by design — which is exactly what an
ownership-planting attack abuses: park liability-bearing material
in a victim's tenant so possession attributes to them. Two
defenses, both structural. Externally-pushed material lands
*custody-pending*: provenance records the arrival (source, channel,
time, authenticating principal if any), and the record joins the
tenant's owned set only on acceptance — by the owner, or by a
standing rule the owner set. And provenance itself is
runtime-written at the door: forged ownership metadata fails
attestation (§13.4), and every record's creating context names the
principal and channel that produced it — the audit trail attributes
the *act* of delivery, never mere presence. "Possession is not
rights" (§12) has a mirror: delivery is not liability.

**Derivation rule (the IP boundary).** What the system synthesizes
about a person is personal-owned regardless of source provenance —
the norm that has always governed resumes: the experience is the
person's, the documents are the employer's. The source restriction
travels as *inherited disclosure*, not as ownership: a derived record
built from confidential org material persists in the person's tenant
but cannot export the confidential specifics. Derived records inherit
the **most restrictive** disclosure of their sources. On exit, the
employer keeps (and re-closes) its documents; the person keeps the
derived identity, still gated.

**Commission rule.** Output produced under an org's process for a
person's benefit belongs to the person; the org gets visibility
relations. Deployments that genuinely need official-record semantics
flip this by an explicit org-level policy set at onboarding — an
opt-in with a name and a visible cost, never the architecture's
default.

One more empirical warning from the source system: the bigger
migration risk was not user-keyed data but **unowned data** — shared
corpora, catalogs, event logs, license state that were install-scoped
world-state with no owner at all. Migration must *add* ownership to
those, not merely re-scope what already had a key.

## 7. Offboarding and portability

Offboarding is the model's proof. It composes structurally:

1. **Tuple delete.** Removing the employment/membership and seat
   tuples revokes the org's relations into the person's tenant and
   the person's access to org-owned records. The personal tenant and
   everything it owns are untouched. Nothing converts, because
   nothing was ever the org's.
2. **Entitlement gates compute, never data** (§3). Losing the seat
   never locks reading or exporting one's own tenant. The path from
   corporate to personal use is a re-license against the same tenant,
   not a migration.
3. **The takeaway export is first-class and always available.** The
   person can export their tenant at any time — not only at
   offboarding, by which point the hardware may already be
   surrendered. Contents: exactly the records the person's tenant
   owns, each filtered through the disclosure export gate. Org-owned
   intake is excluded *by ownership* before disclosure even applies —
   so the bundle cannot contain employer IP by construction, which is
   the whole defense. Restore is a tenant restore (ids, provenance,
   disclosure intact), not re-ingestion.
4. **The takeaway right is hard policy with no configuration
   surface.** There is deliberately no org setting, policy pack, or
   deployment flag that disables or narrows it — the existence of the
   switch is what invites the procurement demand, and the bundle is
   clean by construction, so there is nothing of the org's to
   protect. Any commercial exception is a per-contract bespoke fork,
   priced as such — not a feature.

The asymmetry is the point: the employer keeps its documents; the
person keeps their derived identity; neither side keeps the other's
property. In product terms the takeaway export is also the retention
funnel — the departing member leaves with their tenant in hand and a
personal re-license path, instead of both sides losing the
accumulated value.

Side effect worth naming: the personal tenant is a natural unit of
erasure and export, which is the shape GDPR-style
portability/deletion requests want.

## 8. Defense — alternatives rejected, objections answered

### Alternatives (each considered and rejected in the source ADR)

- **Owner column only, no context.** Adds ownership but keeps
  operator and subject conflated in one identity — which is the
  actual bug observed in practice (import paths and boundary builders
  silently stamping operator = subject = owner).
- **Full multi-tenant infrastructure now** (identity provider,
  hosted relation engine, membership admin). Builds hosting machinery
  with zero hosted users, against local-first constraints. The model
  is adopted now precisely because the *vocabulary* is cheap and the
  *infrastructure* is deferrable behind the `check` seam.
- **Do nothing until hosting.** Every new feature keeps minting
  string-keyed records and ownerless world-state; shapes harden into
  de facto contracts that must be re-keyed under live load. (Observed
  concretely: a content-address formula that embedded the user string
  — see migration §M3.)
- **Tenancy as tiers on the confidentiality enum.** Conflates
  disclosure with access; someone gains access by a record being
  "public" within the wrong boundary. The axes must stay orthogonal.
- **Dual stamping: `tenant_id` + `subject_id` on every record.**
  (The v1 draft of the source ADR.) Ownership becomes two-keyed
  everywhere; offboarding requires moving or re-keying data out of
  the employer's tenant; and it invents an identity layer that
  people-are-tenants gets for free.
- **Containment: personal tenants nested inside org tenants.**
  Uniform ownership, but the org transitively holds personal data —
  offboarding becomes data migration. Zanzibar has no containment
  primitive anyway; hierarchy is tuples plus rewrite rules, so the
  relation form is the same expressiveness without the trap.
- **Org owns everything** (the industry-standard corporate-workspace
  model). Rejected as the *default* on three grounds: the member's
  data dies on exit, killing any data-outlives-membership value
  proposition; it is meaningless in bottom-up adoption where no org
  tenant exists; and it is unnecessary — official-record deployments
  get the semantics as a named, explicit policy flip.
- **Person owns everything, including org-channel intake.** The
  mirror error: the employer's documents walk out with every
  departing employee. Enterprise buyers will not accept it, and it misstates the
  IP reality the provenance rule encodes.

### Objections and answers

**"Why ReBAC instead of RBAC/roles?"** Roles are facts someone
granted; tuples make grant and revoke first-class and auditable.
Usersets express groups (`asset#viewer@tenant:org#employee`) without
a parallel groups system. RBAC is expressible inside ReBAC (a role is
a relation); the converse — per-object, per-person sharing — is
where RBAC deployments grow ad-hoc side tables.

**"Isn't this over-engineering for a single-user product?"** The
local resolver reduces the machinery to constant context fields and
trivially-true checks — measured cost is a parameter through
signatures. What it buys even single-user: new code asks "may this
principal see this record" instead of assuming yes, and negative
tests exist before the second principal does. A personal deployment
stays a tenant of one indefinitely; the cost never grows.

**"Where's consistency handled? The new-enemy problem?"** In the
hosted engine, deliberately. Zanzibar-class systems solve stale-tuple
/ new-enemy ordering with snapshot tokens (zookies); the domain keeps
them out of its schema by treating `check` as the seam. Locally the
question is moot (one principal, in-process function).

**"Performance of check-per-read?"** Locally: an in-process function
short-circuiting on `#owner`. Hosted: the engine's caching and
denormalization problem — Zanzibar's published design and its open
implementations exist precisely because this is solvable at scale.

**"Why not per-tenant databases or row-level security now?"** Both
are physical isolation strategies, not models. Columns + scoped
queries + the check seam preserve the option; choosing cells/RLS
early buys operational cost before any hosted user exists.

**"What stops the org from demanding the takeaway switch?"** Nothing
stops the ask; the architecture removes the surface. The bundle
contains no org property by construction (ownership excludes it;
inherited disclosure gates the residue), so the demand has no
technical object — and the absence of the knob is deliberate policy,
with the bespoke-contract fork as the priced escape valve.

**"Authentication is deferred — isn't that a hole?"** It is a
separate layer with a reserved slot (`principal`), not an omission
inside this model. Self-resolution is the posture of dev, test, and
trusted-internal builds; shipped paid tiers are identity-backed from
day one, and the shipped free tier picks its posture per product
(§5). The slot fills without the shape changing — which is the
point of reserving it.

## 9. Migration pattern (from a one-string system)

Generalized from the source system's five-step sequence; each step
leaves the product fully working, none requires login or hosting.

- **M1 — identifiers + context.** One id-validation component adopted
  by every store that embeds ids in paths/rows/hashes; the
  `InvocationContext` schema; a local resolver at each entry point.
  No storage change.
- **M2 — thread the context.** Entry points build a context;
  pipeline/store signatures take it; bare user-id positional
  arguments removed; wire field renamed `user` → `subject`.
- **M3 — stamp ownership.** `owner_tenant_id` on owned records per
  the §6 defaults; owners *added* to previously unowned world-state.
  Existing id strings are registered as personal tenants and adopted
  **verbatim** — landmine: if any content-address or hash embeds the
  id string, renaming re-keys the store; values must survive
  migration untouched.
- **M4 — kill derived-identity conventions.** Explicit run/scope
  fields replace string-concatenation identities.
- **M5 — relations + tests.** The `check` seam + local tuple checker;
  operator/subject taken from context everywhere they were collapsed;
  disclosure-inheritance and negative-authorization tests land.

Sequencing rationale: vocabulary before storage (M1–M2 make every
call site say what it means), storage before enforcement (M3 gives
`check` something to materialize `#owner` from), enforcement last
(M5) when it can be tested against real shapes.

## 10. Instantiations

Instantiation records live with their adopters, not here: an adopting
system documents its own mapping (and its own migration state) in its
own repository, at the Ariadne version it adopted. Miniforge's is
[rfc-ariadne-adoption.md](../rfc-ariadne-adoption.md).

Keeping this section empty is deliberate. A portable architecture that
accumulates adopter-specific tables stops being portable, and adopters
should not have to read someone else's domain to find the mechanism.

## 11. Agent orchestration: principals, not tenants

Agents (single or multi-agent systems) fit the model without new
concepts — but only if they land on the right side of the
tenant/principal split.

### 11.1 An agent is a principal that owns nothing

Tenants exist to own, and an agent must own nothing. The rule that
reconciles this with §6: **execution artifacts follow the launching
context; domain outputs follow the ownership defaults for the
artifact class they become.** Run state, working memory, and
execution traces belong to the launching/operating tenant (the
session is a channel, and the channel has an owner). What the run
*produces* is classified like any other record: subject-derived
output → the subject's personal tenant (the commission rule); an
org operational report → the org tenant. A runtime that stamps all
agent outputs with the session owner silently bypasses the
derivation and commission rules — that is the bug this rule exists
to forbid.
Giving an agent a tenant creates an ownership boundary where a
person's or org's records can land as the property of a process —
then decommissioning the agent becomes data migration, which is the
containment trap (§8, nesting) rearranged.

The model already reserves the slot: a principal maps to a personal
tenant *usually* 1:1. An agent principal is the exception — a
principal with no tenant behind it. Each agent instance gets its own
principal id and never operates as the human's principal.
Impersonation is banned by construction; delegation is explicit.

### 11.2 Delegation is granted; attenuation is monotone

A delegation is a **first-class grant object**, not a bare tuple.
Tuples express its reachability; the grant record holds identity,
constraints, and lineage:

```text
delegation:D#grantor@principal:alice         ; who delegated
delegation:D#grantee@principal:agent-a       ; to whom
delegation:D#basis@engagement:alice-acme     ; why it exists (lineage)
run:R#delegation@delegation:D                ; which run rides it
```

with the grant record carrying scope, purpose, argument constraints,
allowed capability versions, `:delegable?`, TTL, policy version, and
revocation state (§13.6). Two simultaneous delegations between the
same human and agent can then differ in scope and expiry without
colliding — and lineage points at an immutable grant *generation*,
so recreating a same-named relation later never resurrects a
descendant's validity.

Consequences:

- **Audit.** Every action is attributable to the agent principal;
  on-behalf-of derives from the delegation object. No log
  archaeology to distinguish the human's hand from the agent's.
- **Revocation.** Cutting the delegation (or its basis) is
  authoritative immediately; the runtime completes it — commit-time
  recheck, lease fencing, stale-result rejection (§13.5). No data
  moves, same shape as offboarding.
- **Blast radius.** A rogue, buggy, or prompt-injected agent can
  reach exactly what its grants allow and nothing else. This is the
  central argument: injected text can talk the model into *wanting*
  anything, so authority must live outside the model — in grant
  objects the injected text cannot write.
- **Sub-agent chains.** Delegating requires the separate
  `:delegable?` authority — holding a capability never implies the
  right to pass it on — and a child's constraints must fit within
  the parent's *delegable* constraints. Attenuation stays monotone
  through orchestration depth; the confused-deputy escalation path
  is closed structurally.
- **Expiry.** Agent grants are task-scoped and short-lived by
  default (condition TTLs); locally the resolver bounds the
  lifetime.

### 11.3 Tools: the agent proposes; the runtime decides

An agent never invokes an effectful tool. It **proposes a
transaction** — capability version, binding, canonical arguments,
labeled input references, destination, expected effect — and the
deterministic runtime evaluates the proposal across three planes
(§13.3):

1. **Authority** — capability grant, object access, and grant
   lineage: may this principal use this binding, on these records,
   under a still-valid basis? Tool bindings are tenant-owned assets
   (same class as connector bindings), so who may invoke them is
   ordinary relation vocabulary; store-level checks run with the
   agent principal in the invocation context — no parallel
   enforcement path for data.
2. **Information flow** — do the inputs' policy clauses admit this
   destination, for this purpose (§11.4, §13.1)?
3. **Effect** — is the operation's impact class within the grant
   (§13.3)?

Parameter-level constraints — "only these recipients," "under this
spend cap" — are argument constraints on the grant and binding, and
the *classification* of arguments is never the model's to assert:
trusted destination-resolution code derives the destination from
canonical arguments, the capability version fixes the possible
effect classes, and the resolver establishes purpose (§5). The
model proposes arguments; it does not classify their consequences.

### 11.4 Tools are a disclosure surface

Every tool invocation with effects beyond the process is a
disclosure event: data crosses a boundary toward some audience. The
record-side policy (confidentiality × exportability) already says
what each record permits; the missing half is what audience the
tool's effect reaches. So each tool binding carries an **egress
audience**:

```text
local      — computes in-process; no egress (a parser, a scorer)
tenant     — effects stay within the operating tenant's boundary
related    — reaches principals in related tenants (a shared
             workspace, a commissioner's view)
external   — leaves the trust boundary entirely (email out, web
             POST, third-party API)
```

The invocation rule: every input to the call — records, derived
values, context taint (below) — contributes its **policy clauses**
(§13.1), and the resolved destination must satisfy *every* clause.
The ladder above is UX shorthand; the decision compares a
structured destination descriptor against each clause's constraints
(§13.2).

- Every clause satisfied: proceeds, silently.
- A clause unsatisfied, where its relaxation mechanism permits:
  **human-in-the-loop elevation** — approved through that clause's
  authority, per invocation, and recorded.
- A clause whose relaxation mode is *none* (`never-export`-class):
  refused. No ask path exists, so the queue cannot be farmed by
  injected content.

This is label-based information-flow control (decentralized, per
Myers–Liskov: each clause carries its authority) with tools as the
declassification points and elevation as the declassifier. Two acts
must stay distinct: **elevation never relabels** — it is a flow
exception attached to (payload, destination, invocation), and the
record keeps its clauses; a *persistent* relaxation is the separate
act §13.1 defines, authorized by every affected clause's authority.
Conflating them makes every approval quietly launder the record for
all future flows.

Two properties make this hold against adversarial content:

- **Inheritance closes the laundering hole.** Derived values carry
  every source clause (§13.1), so summarize-the-confidential-doc-
  then-email-the-summary meets the same gate as emailing the
  original. The agent cannot downgrade by transformation — only an
  authority-defined policy transformation, executed by a trusted
  capability version, removes a clause (§13.1).
- **Elevation is human-only and out-of-band.** The elevation
  affordance is not a tool the agent can call with arguments it
  authored — otherwise injection simply asks for elevation politely.
  It is a channel to the human that renders exactly what will cross
  the boundary, scoped to that invocation, never a standing setting.
  The two downgrade paths — writing tuples, approving elevation —
  both live outside the model channel. That pair is the whole
  security argument for agentic operation.

The friction objection answers itself: the audience comparison
silences `local` and `tenant` tools entirely; elevation fires only on
above-audience flows, which is precisely where a human should be
looking. The thing to resist is *unscoped, self-service* standing
grants — they are how scope creep returns; the disciplined form is
§11.5's scoped grants. Frequent elevation prompts remain a
diagnostic: either labels are wrong or payloads are too coarse.

Three known limits, conceded up front:

- **Label creep.** Clause union ratchets monotonically: long
  derivation chains accumulate restriction, then elevation fatigue,
  then rubber-stamping. The corrections are authority-defined
  policy transformations (§13.1 — the legitimate clause-removal
  path), granular payloads (a call that sends only what it needs
  carries fewer clauses), and clause normalization/subsumption so
  semantically identical clauses do not multiply.
- **Aggregation.** N individually-public records can be jointly
  sensitive; the union of empty clause sets is empty, so the
  mechanism passes what the compilation discloses. The model's
  partial answer: aggregates are derived records and may receive
  clauses at creation exceeding their sources'. Automatic detection
  of jointly-sensitive aggregates is out of scope.
- **Context taint.** An agent's working context is itself a labeled
  surface: tool results flow in and later flow out through other
  tools. The enforced v1 is scoped, not global (§13.4): an
  artifact's clauses come from its actual inputs; an agent's
  context carries the union of what that agent actually read. The
  agent's reply to its human is an egress channel like any other —
  its audience is the delegating principal, which is why it is
  usually silent.

### 11.5 Elevation at scale: scoped grants and label authority

Per-invocation elevation does not survive contact with real
workloads: the human approving the fifteenth identical flow stops
reading, and fatigue converts the gate into a rubber stamp. The fix
is not weakening the gate; it is letting an approval mint a **scoped
grant**:

```text
once       — this invocation only (the default)
session    — this agent session; dies with the context it was
             evaluated against
context    — a named scope (a repo, a workspace, a review cycle),
             time-boxed
permanent  — not a grant: a persistent relaxation through the
             clause authority's mechanism (§13.1)
```

- **Grants are tuples with conditions** (TTL, session id — SpiceDB
  caveats, OpenFGA conditions), living in the same store as all
  other authority: auditable, revocable by delete, expiring by
  default. This is just-in-time privileged access (PIM, in
  Microsoft's vocabulary): time-boxed, context-scoped,
  approval-routed, re-certified — never silently permanent.
- **Grants attach to flows, not tools.** A grant names (principal,
  record class, audience, scope). Granting the *tool* reopens the
  laundering hole — any payload rides the approved channel. This is
  the gap in current agent harnesses: "always allow this tool" is a
  channel bypass, not a flow decision.
- **Session scope is taint-coherent.** The approval was informed by
  what the session had read; a new session is new taint, so
  re-approval is the correct default, not friction.

**Label authority: a clause is relaxed only through the mechanism
its imposing authority defined.** Record ownership is not enough.
Self-service relaxation — including persistent transformation —
applies only to clauses your own tenant imposed. A record you own
can carry a clause that is not yours: a claim derived from another
tenant's confidential sources inherits their clause (§6, §13.1).
The record is yours; that clause is theirs; relaxing it goes
through *their* declared mechanism — approval workflow, quorum,
policy transformation, automatic embargo release, or none (§13.1).
Authorities need not be tenants: licensors, contracts, and
compliance regimes impose clauses without residing in the system
(§12). You cannot change the escalation surface of a clause you did
not impose, no matter what you hold.

The clause's authority publishes the **relaxation workflow**: for
this record class × destination × requesting principal, which
scopes may be granted, by which approvers, at what TTL. Requests
route to those approvers; the agent may request — the request
carries the rendered payload and its clauses — and never
self-approves. A relaxation mode of *none* keeps no request path,
so the queue cannot be farmed by injected content. And **an
approval request is itself a disclosure**: rendering the payload to
an approver is an information flow, so the approver must be
entitled to see it — otherwise the workflow shows a policy-safe
redacted preview or routes to a differently-authorized approver
(§13.3). The model cannot bypass disclosure by asking a broadly
visible queue to display the data.

This is the decentralized label model (Myers–Liskov): labels carry
their policy's owner, and only that owner declassifies. The PIM
framing and the DLM framing are the same design seen from
operations and from theory.

### 11.6 When the tool and the domain are the same thing

Email complects the two: it is a tool (send) and a domain (messages,
threads, a mailbox that is itself owned data). The decomposition uses
axes the model already has:

- **The domain records** — messages, threads, the mailbox — are
  ordinary owned records. A connected mailbox is an intake channel;
  the provenance rule stamps ownership; *reading* mail through the
  tool is intake plus store-checked reads. No egress ceiling is
  involved in reading.
- **The channel** — the send binding — is a tenant-owned asset with
  an egress audience.
- **The audience may be argument-dependent.** A recipient field means
  the binding has no single static ceiling: sending to yourself, to a
  teammate, and to an arbitrary third party are different audiences.
  Resolve the audience per invocation from the argument (self /
  same-tenant / related tenant / external), then run the same
  record-versus-audience comparison. Where per-invocation resolution
  is unwanted, split the binding by destination class — send-to-self,
  send-within-org, send-external — each with a fixed ceiling and its
  own capability tuple.

The model survives the tool/domain entanglement because "what may
be disclosed" has always meant "to whom, for what" — a clause
constrains destinations, not tools. The tool never had one ceiling;
the *invocation* has one resolved destination. The recipient
argument resolves the destination descriptor (by trusted resolver
code, §11.3); the payload's clause set resolves the data side; the
clause-versus-destination decision (§11.4, §13.2) is unchanged.

### 11.7 Ownership mapping and objections

| agent artifact | disposition |
|---|---|
| definitions — prompts, configs, tool bindings | shared assets, owned by the authoring tenant (same class as lenses) |
| instances | principals: tenant-less, tuple-scoped, expiring |
| run state, working memory, execution traces | the launching/operating tenant (execution artifacts) |
| domain outputs | the §6 defaults for what the artifact becomes — subject-derived → personal; org report → org |
| compute | billed to the delegating tenant's entitlement |

**"Why not just sandbox the agent?"** Sandboxing bounds the process;
tuples bound authority; disclosure bounds egress. Different failure
modes — and only the last two survive the agent moving to hosted
compute, gaining a new tool, or spawning children.

**"Can't a well-tested agent be trusted with elevation?"** The gate
is not about the agent's intent. The agent's input channel is
writable by adversarial content, so any authority reachable from the
prompt is reachable from an injection. Trustworthiness of the model
does not change who can write to it.

**"Doesn't this slow orchestration down?"** The capability check is
one tuple lookup; the data checks were already there; the audience
comparison is an enum compare except when it correctly stops
something. The expensive path — elevation — is rare by construction
and expensive on purpose.

## 12. Third-party rights: licensed and public intake

Connector intake stamps ownership by the channel's tenant (§6), and
disclosure labels classify what each record permits. Both are
insufficient for payloads whose underlying rights belong to someone
outside the system: a licensed data feed, a scraped web page.
**Possession is not rights.** The owner column says who custodies
the record inside the system; it cannot say the record is yours to
keep, redistribute, or retain past a contract date. Conflating the
two accrues legal liability at intake speed.

### 12.1 The rights label and the external authority

Records carry a rights component alongside disclosure:
`{rights-holder, license-ref, terms-class}`, with terms classes:

```text
owned                — the custodian tenant holds the rights
licensed             — use-bound: retention, redistribution, and
                       derivation limits, with an expiry
public-domain        — affirmatively determined, not assumed
third-party-unknown  — provenance known, terms unknown (the default
                       for open-web intake)
```

The label-authority rule (§11.5) extends without modification: a
license term is a restriction imposed by an authority that is not
resident in the tuple store. No principal inside the system can
lower it — compliance or out-of-band renegotiation are the only
moves. The decentralized label model was built for exactly this:
the policy's owner need not be a member of your system to be the
only party who can relax it. In the executable form (§13.1) rights
and disclosure are one mechanism: a rights label is a policy clause
whose authority happens to be non-resident — not a parallel label
system.

### 12.2 License as a relation object; expiry as offboarding

```text
record:R#rights@license:market-data      ; readability routes through
                                         ; the license node
license:market-data#beneficiary@tenant:alice ; time-boxed grant
```

- **Access to licensed payloads routes through the license
  object,** evaluated at check time. Expiry or invalidation cuts
  one relation and severs every record behind it — no re-stamping
  of a million rows. Structurally this is offboarding again: the
  licensor is a non-resident tenant, the license is the employment
  tuple, expiry is the tuple delete.
- **Expiry semantics follow the terms:** quarantine first (checks
  fail, payload held through a grace/renewal window), purge where
  the contract requires destruction. Content-addressing splits the
  cost: the catalog entry and fingerprint are the custodian's and
  survive; the payload evicts; renewal re-fetches into the same
  identity.
- **No tension with "entitlement gates compute, never data"
  (§7).** That invariant protects the tenant's *property* from a
  product-license lapse. Licensed payloads were never the tenant's
  property — the license is the only path to them, so the path
  dying with the license is the invariant working, not an exception
  to it.

### 12.3 Derivation across a license boundary

Disclosure inheritance stays most-restrictive (§6). Rights
inheritance is **term-driven**: the license object carries a
derivation clause stating what derived records may be. A financial
feed of this kind licenses the raw series for use with
retention bounds while derived analytics belong to the analyst —
so raw quotes carry `licensed` with expiry, and the model's own
outputs over them carry `owned`. Absent explicit terms, derived
records keep the third-party rights label — the safe default.

This is consistent with the claims-are-the-IP-boundary rule (§6),
not a second system: there the governing agreement is the
employment norm (the resume convention); here it is the license
contract. In both, what crosses the derivation boundary is decided
by the governing agreement — the model just makes the agreement
machine-readable instead of hard-coding one norm. In §13.1 terms,
a derivation clause *is* an authority-defined policy
transformation: clause union is the default, and only a trusted
capability version applying the licensor's declared transform may
drop the raw-data clauses from the analytics — emitting proof of
which transform ran.

### 12.4 The open web: public is not public domain

Copyright defaults on — a scraped page has an author and unknown
terms, and "publicly reachable" grants nothing. Default label for
open-web intake: `third-party-unknown` with ephemeral-use terms:

- transient retention, scoped to the task or session;
- derived claims and summaries permitted — facts are not
  copyrightable, expression is; the derivation carries the
  third-party label until determined otherwise;
- raw-payload retention, redistribution, and external egress
  refused by default.

Upgrading the label is a **recorded human determination** — a
license discovered, terms of service that permit, public-domain
status confirmed — through the same elevation machinery (§11.4–
11.5), with the determination and the determiner in the audit
trail. The liability position several frontier labs bought into
class actions is precisely the inverse: no rights label at intake,
retention by default, and no record of anyone deciding anything.
This architecture makes the conservative label the default and the
risky position a named human's recorded decision.

Fetch terms ride the binding: a web-fetch binding carries
per-source terms knowledge (API terms, site ToS) the same way
connector bindings carry egress audiences — the channel knows its
contract in both directions.

**The takeaway export filters by rights as it filters by
ownership.** Licensed payloads and unknown-rights raw payloads stay
out of the bundle; manifests and pointers — the custodian's own
records — ride, so a departing person re-fetches under their own
license rather than carrying the licensor's property out.
Employer-IP-clean, licensor-clean, same construction.

## 13. The executable contract

Everything above is the conceptual model; this section is what the
model compiles to when a runtime enforces it. Sourced from an
adversarial review of v1.3; each subsection is the simplified form
of a gap that survived scrutiny. The organizing thesis is worth
stating once:

> **Models propose. Deterministic authority, information-flow, and
> effect-control systems decide and execute.**

### 13.1 Labels are clause sets, not scalars

A scalar "most restrictive" label loses *whose* restriction it is
when sources from different authorities combine — derive from
Acme-confidential and licensor-restricted material and one enum
cannot say who may relax the result. The normative label is a set
of clauses:

```clojure
{:policy-clauses
 [{:authority [:tenant "acme"]        ; resident imposer
   :allowed-operations #{:read :derive}          ; no :export — operations
                                                 ; ARE the exportability axis
   :allowed-purposes   #{:service-delivery}
   :destination-constraints                      ; what the decision
   {:trust-zones       #{:local :tenant}         ; compares against the
    :regions           #{:us}                    ; destination descriptor
    :max-retention     :zero                     ; (§13.2)
    :training-use      :forbidden}
   :validity   {:valid-until nil}
   :relaxation {:mode :approval-workflow         ; HOW exceptions are
                :workflow-ref "acme-release-v3"}} ; authorized (§11.5)
  {:authority [:license "market-data"] ; non-resident imposer (§12)
   :allowed-operations #{:read :derive}
   :allowed-purposes   #{:analysis}
   :destination-constraints {:trust-zones #{:local}}
   :validity   {:valid-until #inst "2099-01-01T00:00:00Z"  ; placeholder
                :on-expiry :deny}                ; expiry POLARITY: a
   :relaxation {:mode :policy-transform}}]}      ; license dies closed;
                                                 ; an embargo would say
                                                 ; :on-expiry :release
```

- **Derivation = clause union.** A derived value carries every
  source clause; effective permission is the intersection of what
  the clauses allow. "Most restrictive input governs" survives as a
  consequence, with authorship intact.
- **Elevation must satisfy every applicable authority**, not the
  strictest one — two imposers means two approval routes (§11.5).
- One mechanism covers disclosure, org restrictions, licensed
  rights, and third-party rights. Exportability is not a parallel
  enum: it is the `:export` member of a clause's
  `:allowed-operations`. The four coarse classes and the audience
  ladder remain as UX and indexing shorthand over this.
- The declared `purpose` on the invocation context (§5) is checked
  against clause `allowed-purposes`: reading for service delivery
  does not license training, cross-customer analytics, persisted
  embeddings, or marketing. Purpose is a dimension of the
  disclosure axis, not a fifth layer — and it is rooted in a
  `purpose-basis` (§5), never model-asserted.
- **Expiry has polarity.** `:on-expiry :deny` (a license: permission
  ends, payload quarantines) and `:on-expiry :release` (an embargo:
  the restriction ends) must never share an ambiguous field —
  dropping an expired license clause by default would silently
  broaden access.
- **Relaxation is machine-readable per clause** — approval workflow,
  quorum, policy transform, automatic release, or none — not
  inferred from the authority's kind (§11.5).
- Clause sets are **normalized**: deduplication, subsumption, and a
  content-addressed policy digest keep union bounded through long
  derivation graphs and make labels cacheable and comparable.

**Authority-defined policy transformations** are the one legitimate
clause-removal path, resolving clause union against term-driven
derivation (§12.3). Default: output clauses = union of input
clauses, always. A clause is removed or rewritten only when the
deterministic runtime applies a transformation the clause's
authority declared:

```clojure
{:transform-id        "market-data-derived-analytics-v2"
 :authority           [:license "market-data"]
 :input-clause-match  {:license-ref "market-data"}
 :consume-clauses     #{:raw-retention :raw-redistribution}
 :emit-clauses        #{:citation-required}
 :capability-versions #{"analytics-engine@4.2"}  ; ONLY these may run it
 :proof-ref           "contract-clause:12.4"}
```

The output records which transform ran, over which input policy
digests, producing which output digest — attested by the runtime.
The same mechanism covers trusted redaction, extraction of
non-sensitive facts, approved-mechanism aggregation, embargo
release, and the employer-evidence → employee-claim conversion (§6).
The model never decides a transformation succeeded; a trusted
capability version performs it and emits proof. Without this
concept, either union overtaints forever or application code drops
clauses ad hoc — model-mediated declassification by the back door.

### 13.2 Destinations are structured, not rungs

`local < tenant < related < external` is not a total order: a
tenant can be 50,000 people, "related" can be one contracted
partner, "external" can be the subject's own inbox, and a local
plugin can leak telemetry. The decision evaluates a destination
descriptor — recipient set, trust zone, tenant boundary, retention,
training-use — against each clause. Keep the ladder for display;
never let it be the thing the policy engine compares.

### 13.3 Effects are a separate plane from egress

Deleting a database discloses nothing. Every invocation carries two
independent classifications: an **egress class** (where information
flows — §11.4) and an **effect class** (what state or commitment
changes): observe / derive / reversible write / irreversible or
high-impact (financial, legal, privileged-administrative,
destructive). The tool model is three planes, each with its own
gate:

1. **Authority** — may this principal invoke this capability?
2. **Information flow** — may these inputs reach this destination?
3. **Effect** — may this principal cause this operation and impact?

**Effects are transacted, not invoked.** The model never calls an
effectful tool; it *proposes a transaction* (the mixture-of-agents
(MoA) transacted-blackboard experiment is this plane's
implementation:
proposals land on a shared blackboard, and the deterministic
runtime is the only committer). The protocol: propose → prepare →
authorize (all three planes) → approve if the effect or egress
class requires a human → commit → reconcile → receipt.
Approval binds to a hash of the exact transaction (arguments,
rendered payload, destination, capability version, policy
snapshot); any change after approval invalidates it. Commits carry
idempotency keys and an `unknown-outcome` state with reconciliation
— an effect that may or may not have happened is a first-class
state, not an exception.

**The full decision operation is `decide`, not `check`.** `check`
remains the ReBAC primitive; the runtime's question is
`decide(context, operation, binding, labeled-inputs, destination,
proposed-effect)`, returning a **decision envelope**, not a
boolean: allowed?, reason codes, obligations (redact this field,
zero provider retention), required approvals, and the revision pins
— relation revision, policy digest, grant generations, destination
and effect-scope digests, validity window. The envelope goes into
the approval hash, the commit-time recheck, the effect receipt, the
audit trail, and fallback-eligibility evaluation. A rejection is
debuggable because the envelope says why.

### 13.4 Taint is enforced, and model calls are egress

The v1 taint representation is chosen, not aspirational — and it is
**scoped**, which matters the moment a run has more than one agent
on a shared blackboard:

```text
artifact label      = union (or transform) of the artifact's
                      ACTUAL inputs
agent-context taint = union of the artifacts that agent actually
                      read
run policy summary  = conservative upper bound, for audit and
                      fail-safe use only
blackboard          = a container — placement on it is NOT a flow
                      between everything on it
```

Agent A reading a confidential document taints A's context and A's
outputs — not agent B's public-only branch that never read A's
artifacts. Globally tainting every branch would destroy the value
of parallel orchestration, and provider eligibility for a subtask
is computed from that subtask's *actual* labeled inputs, not the
most restrictive fact read anywhere in the parent run. Tools
receive labeled values or artifact references, never bare strings;
finer-grained (claim/span-level) provenance is a later optimization
against label creep, not a prerequisite. And a model call is itself
an egressing tool: the provider is a destination with retention,
training, and residency attributes, evaluated like any other —
which is what makes model routing a policy question (§13.6).

**The label plane is writable only by the runtime** — the dual of
the injection argument (§11.2): the doorkeeper has no
natural-language input, and the model has no label output. Values
travel as payload plus policy reference; the model's output channel
is payload only. Output clauses are *computed* from the inputs
actually read, never declared by the model — so a forged in-band
label is just content: it hashes as payload and parses as nothing.
A model claiming a transform ran fails identically, because
transform receipts are runtime-attested against the trusted
capability registry (§13.1). Label forgery is not detected; it is
unrepresentable. The remaining attack surface is exactly the
legitimate declassification points — transforms and elevation —
which are trusted-capability-only and human-only by construction.

### 13.5 Revocation: authoritative at the store, effective at commit time

"One tuple cut severs everything" is true of the relation state and
insufficient for enforcement: caches, in-flight calls, queued work,
and already-authorized workers all outlive the delete. The
transacted-effect protocol closes the gap structurally — every
commit rechecks policy, and the recheck has a **freshness
contract**: a consequential commit authorizes against a view at
least as fresh as every relevant revocation/policy watermark the
runtime knows, or uses a fully current check. Never the run-start
snapshot for an external effect; never a worker-chosen older token;
the lease/fencing generation is part of the commit condition, and a
revocation event advances the minimum acceptable revision for
affected workers. Queued and in-flight work fails the recheck;
stale-generation results are rejected; cache staleness is bounded
and stated. This matters locally too: one human principal, but many
workload principals running concurrently.

Three meanings of revocation stay distinct: **authority
revocation** (future actions denied — the relation cut),
**execution cancellation** (queued/in-flight work stopped or its
results rejected — the runtime's job above), and **effect
reversal** — which may be impossible. A prompt already transmitted,
a sent email, a completed payment cannot be un-sent; what crossed
the commit boundary is handled by reconciliation, compensation, and
audit, never by pretending authorization applies retroactively.

**Revocation for cause, and consequences beyond it.** A grant is
conditional — valid only while its terms are followed. Cheap
conditions are monitored in-line (TTL, budgets, argument
constraints); purpose adherence often cannot be gated at the moment
of use and is *audited instead*: decision envelopes, receipts, and
the provenance log make breach visible at reconciliation even when
every gate passed — the pass was real; the detour wasn't. Gates
prevent what they can; the audit trail catches the rest, which is
why receipts exist beyond forensics. Breach then triggers three
grantor-side responses, all declared policy, none ad hoc: the
instance dies (ordinary revocation); the grantor's *granting
policy* for that grantee degrades — future requests are denied or
narrowed for a declared period; and any pre-agreed remedies in the
grant's terms execute — the fine you accepted along with the
library card. For non-resident authorities (§12), the system's
contribution to remedies is attested evidence, not enforcement. Breach history
is also an eligibility input (§13.6): a binding or agent with
recent revocations for cause drops out of routing's candidate set
before ranking, like any other policy filter.

Grants additionally carry **lineage**: each records the basis
relation or grant that justified it, and authorization requires the
grant *and a still-valid basis* — cascading revocation without
sweeping descendants. Delegation is its own authority: holding a
capability does not imply the right to delegate it (`:delegable?`
is a separate, default-false property), and a child's constraints must
fit within the parent's *delegable* constraints, not merely the
parent's own.

### 13.6 Registry: describe, authorize, route, invoke

Four object types, not one tool record: **capability definition**
(semantic operation, schemas, effect class) → **capability
version** (immutable implementation, sandbox/network profile,
idempotency and retry safety) → **tenant binding** (credentials,
destination resolver, provider terms, data-processing profile) →
**execution grant** (why this principal/run may use this binding
now: basis, argument constraints, purpose, expiry). Models are
capability bindings like any other — with modalities, quality,
cost, retention/training policy, residency, and trust boundary.
The routing sequence: discover capability-compatible bindings →
validate the grant and its lineage → evaluate clauses, purpose,
destination, effect → drop candidates forbidden by residency,
retention, training-use, rights, or tenant policy → apply
entitlement, budget, context-window, and availability limits → rank
survivors by quality, latency, cost, health → invoke → stamp the
output with capability version, policy digest, provenance, and any
applied transform. **A fallback re-runs eligibility, not just
ranking** — a tenant-safe model's failure must not silently fail
over to a provider the clauses forbid. Routing is thus not another
policy subsystem: it is an optimizer over the set of bindings the
authority and information-flow runtime already proved eligible.

Six interfaces to freeze before implementing any of this:
`PolicyClause`, `PolicyTransform`, `DestinationDescriptor`,
`ExecutionGrant`/`Delegation`, `DecisionEnvelope`,
`ProposedTransaction`.

### 13.7 Runtime obligations

Compressed; each is a fail-closed contract, not advice:

- **Atomicity.** A record is unreadable until owner, clauses,
  rights, and provenance all exist (transactional write + outbox
  projection; missing policy metadata fails closed). Deletion and
  rights expiry reach derived copies: caches, indexes, embeddings.
- **List authorization.** `check` answers point queries; listing
  uses relation-service list-objects or authorized indexes rather
  than a per-row check loop, and the API rechecks the selected page
  in one batched call at a consistency token before returning
  content — no unchecked search results.
- **Relation mutation is an API, not a function.** The injection
  argument (§11.2) holds only if ordinary application code cannot
  write tuples. Grant management checks grantor authority,
  delegability, scope narrowing, and basis; policy and rewrite-rule
  changes are versioned and audited, because a rewrite change can
  expand access retroactively.
- **Export is a snapshot.** The takeaway bundle binds tenant, time,
  data versions, policy versions, a consistency token, hashes, and
  machine-readable exclusion reasons — not a long series of
  unrelated reads. The no-off-switch rule (§7) stands; externally
  imposed holds (legal preservation, safety, rights expiry) are
  explicit, audited external-authority clauses, never org
  convenience switches.
- **Audit splits.** A tenant-visible activity record owned by the
  tenant; a platform security record owned by the platform security
  domain; correlation ids link them; payload content stays out of
  the security log.

### 13.8 The compressed framing

For presenting the whole system in one breath: every agent run gets
a distinct principal and an attenuated delegation rooted in the
commissioning tenant; models never hold ambient authority; model
and tool calls pass separate capability, information-flow, and
effect gates; inputs keep policy and provenance through the run;
effects are proposed, authorized, optionally approved, idempotently
committed, reconciled, and audited; and routing selects only among
providers policy has already cleared. The personal-versus-org
ownership rules (§6–§7) are the product thesis underneath — the
deeper follow-up, not the opening.

---

The invariants that must survive any instantiation: people
are tenants (A1); one owner per record — owner meaning control and
custody (A2, §6); membership as relation (A3); the four layers stay
orthogonal (§3); ownership follows provenance, type never implies
owner kind (§6); entitlement gates compute, never data; the
takeaway export has no off switch (§7); agents are principals and
own nothing, with child grants fitting within the parent's
*delegable* constraints (§11.2); labels are clause sets — derived
values carry every source clause, and a clause is removed only by
an authority-defined policy transformation executed by a trusted
capability version (§13.1); **a restriction is relaxed only through
the relaxation mechanism authorized by every authority whose clause
would otherwise forbid the operation** — tenants, licensors,
contracts, and compliance regimes alike (§11.5, §13.1); elevation
is a recorded act that never relabels; grants are first-class,
scoped, expiring, lineage-bearing objects that attach to flows
rather than tools (§11.2, §11.5); possession is not rights —
licensed access routes through the license relation and dies with
it, and unknown-rights intake defaults to the most restrictive
plausible terms (§12); egress and effect are separate planes and
effects are transacted — proposed by models, decided as envelopes,
committed only by the deterministic runtime under the freshness
contract (§13.3, §13.5); taint is scoped to artifact, agent
context, and run — a shared blackboard is a container, not a flow
(§13.4); execution artifacts follow the launching context while
domain outputs follow §6 (§11.1); and org-granted roles scope to
engagements, never to a person's whole tenant (§4).
