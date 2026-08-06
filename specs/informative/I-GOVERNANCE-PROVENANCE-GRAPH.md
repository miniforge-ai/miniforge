<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I — Governance Provenance Graph

**Status:** Proposed, informative (non-normative)
**Date:** 2026-08-04
**Version:** 0.1.0-draft
**References:** N1 (repository intelligence), N3 (event stream), N4 (policy
packs), N6 (evidence and provenance), N9 (external PR integration), Ariadne,
I-INCIDENT-DIAGNOSTICS

---

## Purpose

This document describes a **Governance Provenance Graph**: a versioned,
queryable projection connecting code, specifications, policy clauses, rules,
decisions, evidence, incidents, knowledge, connectors, datasets, and reports.

The graph exists to answer cross-system questions whose answers currently
require several independent searches and joins:

1. Which active specification and policy clauses govern this symbol?
2. Which PR introduced the code implicated in this incident?
3. Which later decision superseded the rule an agent used?
4. Which knowledge rules contradict one another?
5. If this connector schema changes, which datasets, reports, and policies are
   affected?
6. Which evidence supports a generated claim, transitively?
7. Is a proposed rule similar to, extending, superseding, or contradicting an
   existing rule?

This is a convergence model, not a new authority system. Git remains
authoritative for code history; normative specifications for product contracts;
policy packs and clauses for policy; Ariadne for runtime decisions and grants;
evidence bundles for execution records; and source systems for operational and
data records. The graph projects those sources so their relationships can be
traversed and explained together.

This document is deliberately non-normative. It defines the intended mental
model, first experimental slice, and normative-gap register without committing
MiniForge Core to a graph database, RDF, OWL, SHACL, or a public graph protocol.

---

## 1. What Kind of Graph This Is

Miniforge already uses several graph-shaped structures. They answer different
questions and should not be conflated:

| Graph | Question | Primary content |
|---|---|---|
| Workflow DAG | In what order should work execute? | stages and dependencies |
| Repository graph | How is code structurally connected? | symbols, definitions, references, calls |
| Lineage graph | How was an artifact or dataset produced? | inputs, transforms, outputs |
| Codex graph | Which known problem or scar is reachable from this situation? | situations, discriminators, problems, resolutions |
| Ariadne relation graph | Who holds authority over which object? | tenants, principals, grants, relations |
| Governance Provenance Graph | What governs, changed, supports, contradicts, or depends on this thing? | versioned projections and evidence-bearing edges |

The Governance Provenance Graph may reference all of these, but it does not
replace their domain semantics. In particular, it never becomes the store used
to authorize an Ariadne decision. A projected `:governed-by` edge can explain
why a policy applied; only the authoritative policy and decision path determines
whether it applies now.

### 1.1 Projection, not source of truth

The graph is a derived read model:

```text
authoritative sources
  git · specs · policy packs · knowledge · evidence · events · data lineage
                              │
                              ▼
                   adapters and canonicalizers
                              │
                              ▼
               versioned nodes + evidence-bearing edges
                              │
                              ▼
              query · impact analysis · incident dossier
```

Rebuilding the graph from the same pinned sources should produce the same
authoritative subgraph. Heuristic edges may vary only when their evaluator or
model version changes, and that version belongs in the edge provenance.

### 1.2 Unknown is not false

Absence of an edge means **unknown**, not “no relationship exists.” A missing
`:governed-by` edge may reflect incomplete symbol coverage, an unindexed policy
pack, a stale projection, or a genuine lack of policy. Query results therefore
carry coverage and freshness rather than silently presenting an incomplete
neighborhood as complete.

---

## 2. Existing Miniforge Primitives

This capability converges contracts and implementations already present in the
repository:

- N1 defines stable repository symbols, structural edges, coverage records,
  Context Pack citations, and structured retrieval reasons.
- N4 defines policy rule identity, applicability, mapping artifacts, repair, and
  a `deprecated-by` relationship.
- N6 defines immutable artifacts, evidence bundles, provenance, parent/child
  artifact relationships, and Data Foundry lineage graphs.
- N9 defines PR context and external-provider integration.
- The knowledge component defines `supports`, `contradicts`, `extends`,
  `supersedes`, and related link types.
- Codex implements reachability from observed situations to known problems,
  resolutions, and scars.
- Ariadne supplies the model-proposes/runtime-decides boundary, version-pinned
  DecisionEnvelopes, and the rule that model output cannot write the authority
  or label planes.

What is missing is a common identity, temporal, edge-provenance, and query model
across these surfaces. The first implementation should connect the existing
facts before inventing new ones.

---

## 3. Conceptual Model

The graph contains immutable **node versions** and **edge assertions**. Stable
logical identities make versions of the same thing discoverable; immutable
version identities make historical answers reproducible.

### 3.1 Provisional node envelope

The following shape is illustrative, not a public contract:

```clojure
{:node/id          string        ; immutable version identity
 :node/logical-id  string        ; stable identity across revisions
 :node/type        keyword
 :node/revision    string        ; blob SHA, digest, version, or source revision
 :node/source      {:source/type keyword
                    :source/ref  string}
 :node/valid-from  inst
 :node/valid-until inst-or-nil
 :node/observed-at inst
 :node/content-ref string-or-nil}
```

Candidate node types include:

```text
code                     governance                operation
----                     ----------                ---------
repository               specification-revision   incident
file-revision            policy-clause-revision   workflow-run
symbol-revision          policy-rule-revision     decision-envelope
commit                   knowledge-revision       evidence
pull-request             decision-record          generated-claim

data
----
connector-schema-revision
dataset-version
transform-version
report-version
```

The graph should use the source system's stable identity wherever one exists.
It should not synthesize a second identity for a commit, policy rule revision,
artifact, evidence bundle, or DecisionEnvelope.

### 3.2 Provisional edge envelope

```clojure
{:edge/id          string
 :edge/type        keyword
 :edge/from        string          ; node version id
 :edge/to          string          ; node version id
 :edge/status      keyword         ; :asserted :derived :proposed :attested
 :edge/basis       [{:evidence/ref string
                     :evidence/use keyword}]
 :edge/producer    {:producer/type keyword
                    :producer/ref  string
                    :producer/version string-or-nil}
 :edge/confidence  double-or-nil
 :edge/rule-ref    string-or-nil   ; deterministic derivation rule
 :edge/valid-from  inst
 :edge/valid-until inst-or-nil
 :edge/observed-at inst}
```

Every non-structural edge explains its basis. A model-proposed relationship
without evidence remains `:proposed`; it is useful for review but cannot be
rendered as established fact. An `:attested` edge names the deterministic
evaluator, trusted capability, or human decision that accepted it.

### 3.3 Edge families

The initial vocabulary should remain small and typed by concern:

| Concern | Candidate edges |
|---|---|
| Code history | `changed-by`, `introduced-by`, `contained-in`, `reviewed-in` |
| Governance | `governed-by`, `applies-to`, `evaluated-by`, `decided-by` |
| Rule evolution | `similar-to`, `extends`, `supersedes`, `contradicts`, `duplicates` |
| Provenance | `supports`, `derived-from`, `produced-by`, `cites`, `refutes` |
| Incident response | `implicates`, `observed-in`, `mitigated-by`, `recurs-as` |
| Data impact | `consumes`, `produces`, `conforms-to`, `transformed-by`, `published-as` |

Inverse display names such as `introduced` / `introduced-by` can be derived at
query time. Storing both directions independently would allow them to drift.

### 3.4 Structural and semantic edges

Structural edges are mechanically reproducible from an authoritative source:

- a commit changed a blob;
- a PR contains a commit;
- a dataset version consumed another dataset version;
- a DecisionEnvelope evaluated named rule revisions.

Semantic edges require interpretation:

- a PR introduced the behavior responsible for an incident;
- a specification governs a symbol;
- one rule contradicts or extends another;
- evidence supports a generated claim.

Semantic edges require explicit evidence and an assertion status. Similarity is
never silently promoted to extension, supersession, or contradiction.

---

## 4. Temporal Semantics

The canonical questions are temporal. “Active rule,” “rule the agent used,” and
“later decision” refer to different times and cannot be answered by reading only
the latest files.

The conceptual model distinguishes:

- **valid time** — when the source fact or relationship applied;
- **observation time** — when Miniforge learned or projected it;
- **execution pin** — the exact commit, rule revision, policy digest, graph
  revision, or evidence version a workflow used.

Example:

```text
rule R1 valid ────────────────┐
                             ├─ superseded by R2
agent run A uses R1 ─────────┘
                                      incident occurs
```

A present-day query may say R2 is active. A replay of agent run A must still
resolve R1 and the DecisionEnvelope that used it. Supersession changes future
applicability; it does not rewrite historical decisions.

Graph queries should accept an explicit `as-of` revision or time when answering
historical questions. When omitted, the result should state the projection
revision and freshness watermark it used.

---

## 5. Claim Provenance

A generated claim is a first-class proposed assertion, not prose appended to a
report. The claim records who or what produced it and the evidence offered in
support.

```clojure
{:claim/id          string
 :claim/text        string
 :claim/subject-ref string-or-nil
 :claim/status      keyword       ; :proposed :attested :refuted :superseded
 :claim/produced-by {:run/id string
                     :agent/id string
                     :capability/version string}
 :claim/support     [{:evidence/ref string
                      :evidence/range {:path string
                                       :blob-sha string
                                       :line-start int
                                       :line-end int}}]
 :claim/confidence  double-or-nil
 :claim/created-at  inst}
```

The graph representation is:

```text
source evidence ─ supports → claim
claim ─ cites → source range
claim ─ derived-from → earlier claim
claim ─ produced-by → agent run + capability version
claim ─ evaluated-by → validator, reviewer, or DecisionEnvelope
refuting evidence ─ refutes → claim
```

Transitive support answers “what ultimately supports this claim?” by walking
through derived claims to source evidence. It does not turn support into truth:
evidence can be stale, ambiguous, contradicted, or insufficient. A claim dossier
therefore includes supporting and refuting paths, unresolved gaps, source
freshness, and attestation status.

The first slice can project claims from existing Context Pack citations and
evidence records without introducing a public `Claim` artifact. A first-class
claim contract belongs in N1/N6 only after the projection demonstrates stable
identity and lifecycle requirements.

---

## 6. Canonical Query Paths

### 6.1 Governing specifications and policy

```text
symbol revision
  → contained-in file revision
  → governed-by specification revision
  → governed-by policy clause / rule revision
  → evaluated-by DecisionEnvelope
```

Applicability may be mechanically resolvable from a file glob, language,
artifact type, AST selector, or registered mapping. Semantic intent that cannot
be reduced to a selector remains evidence-bearing and reviewable.

### 6.2 Incident introduction and mitigation

```text
incident
  → implicates symbol revision
  → changed-by commit
  → contained-in PR
  → evaluated-by tests, reviews, and policy decisions
  → recurs-as known Codex problem or scar
```

“Introduced by” is stronger than “last changed by.” Blame or a recent diff is a
candidate generator; causal attribution requires incident evidence, behavioral
comparison, a reproducer, or an accepted diagnostic decision.

### 6.3 Rule evolution

```text
proposed rule
  → similar-to candidate rules
  → proposed extends / supersedes / contradicts classification
  → applies-to symbols, workflows, datasets, or effects
  → impact neighborhood
  → review / decision
  → published rule revision
```

Lexical or embedding similarity discovers candidates. Deterministic comparison,
formal constraints, or human review decides the semantic relationship. The graph
retains rejected classifications as proposed or refuted evidence rather than
quietly discarding the review history.

### 6.4 Connector schema impact

```text
connector schema revision
  → produces dataset version
  → transformed-by pipeline stages
  → produces normalized datasets
  → consumed-by reports, features, and policies
```

This path composes Data Foundry lineage with policy applicability. It answers
blast-radius questions without making the governance graph the dataset or
pipeline source of truth.

---

## 7. Incident Mitigation Dossier

The first high-value surface is a read-only dossier built from a reported
incident, failed workflow, alert, stack trace, or operator-selected symbol.

The workflow is:

1. Normalize the incident signal and pin the relevant repository and runtime
   revisions.
2. Resolve candidate files and symbols using stack frames, logs, tests, recent
   diffs, repository search, and symbol indexes.
3. Traverse symbol history to commits and PRs.
4. Resolve governing specifications, policy rules, and decisions.
5. Find related Codex problems, scars, prior incidents, and remediations.
6. Traverse callers, dependents, connectors, datasets, reports, and policy
   consumers to estimate blast radius.
7. Generate claims describing likely introduction, governing intent, affected
   surfaces, and mitigation options.
8. Attach supporting and refuting evidence to every claim.
9. Report coverage, uncertainty, stale projections, and missing sources.
10. Submit any effectful remediation through the ordinary Ariadne proposal,
    grant, approval, transaction, and reconciliation path.

The dossier is advisory. It can propose a repair, rollback, policy update, or
new Codex scar; it cannot commit those effects through the graph query path.

---

## 8. Architecture Sketch

```text
┌──────────────────────────────── authoritative sources ────────────────────────────────┐
│ git │ repo index │ PR providers │ specs │ policy │ knowledge │ evidence │ data lineage │
└──────────────────────────────────────┬─────────────────────────────────────────────────┘
                                       │ adapters
                                       ▼
                         ┌────────────────────────────┐
                         │ canonical identity layer   │
                         │ revision + source pins     │
                         └─────────────┬──────────────┘
                                       │
                                       ▼
                         ┌────────────────────────────┐
                         │ projection / edge builders │
                         │ structural + proposed      │
                         └─────────────┬──────────────┘
                                       │
                                       ▼
                         ┌────────────────────────────┐
                         │ replaceable graph index    │
                         │ coverage + freshness       │
                         └─────────────┬──────────────┘
                                       │
                      ┌────────────────┼────────────────┐
                      ▼                ▼                ▼
                graph query     impact analysis   claim dossier
                      │                │                │
                      └────────────────┴────────────────┘
                                       │ proposals only
                                       ▼
                              Ariadne decision path
```

### 8.1 Component boundaries

An implementation would likely separate:

- source adapters that expose pinned source values;
- canonical identity and revision mapping;
- pure edge derivation;
- graph indexing and traversal;
- dossier/query presentation;
- attestation and proposal handoff to existing policy and decision components.

Domain adapters depend on their authoritative component interfaces. The graph
domain should not import PR clients, filesystem walkers, policy internals, or
database drivers directly. Storage remains replaceable behind a graph index
interface.

### 8.2 Storage posture

The first slice does not justify a graph database. An immutable adjacency index
over EDN values, Datalevin, or another existing local store can test the query
model. A specialized graph store becomes justified only when measured graph
size, traversal latency, concurrent updates, or hosted multi-tenant operation
exceeds the simpler representation.

RDF serialization may become a useful interchange format. OWL may add bounded
inference and inconsistency detection, while SHACL may validate RDF graph shapes.
Those are adapter choices, not the domain model or first implementation
dependency.

---

## 9. First Experimental Slice

The first slice is intentionally read-only and repository-local:

```text
Incident → Symbol → Commit → PR
                 ↘ Specification / Rule
                  ↘ Claim → Evidence
```

### Inputs

- a repository and pinned commit;
- an incident signal, failed workflow, or selected symbol;
- available repository symbol and dependency coverage;
- local git history and available PR metadata;
- specification and policy revisions;
- DecisionEnvelopes, Context Pack citations, and evidence artifacts;
- Codex nodes when configured.

### Outputs

- a query result or incident dossier containing versioned node references;
- structural paths and proposed semantic paths;
- claim support and refutation paths;
- coverage and freshness information;
- unresolved identities and missing-source diagnostics;
- no direct mutation or effect capability.

### Deliberate limits

- no public graph artifact schema;
- no graph lifecycle events;
- no stable external API;
- no automatic acceptance of causal or rule-relationship claims;
- no graph database dependency;
- no graph writes to policy, knowledge, git, evidence, or Ariadne stores.

These limits allow the feature to exercise existing N1, N4, and N6 contracts
before proposing new ones.

### Experimental implementation

The repository-local slice is implemented by the `governance-provenance`
component. Its read-only `build-dossier` interface pins a git revision, projects
operator-selected symbols or immutable ranges through blame-derived commits and
local merge ancestry, and attaches explicit specification mappings and
applicable policy rules when supplied.

The implementation emits `changed-by` candidates, evidence-bearing claims,
coverage, and unresolved-source gaps. It does not emit `introduced-by`, query a
remote provider, infer missing symbols, or authorize remediation.

---

## 10. Evaluation

The experiment should use a golden set of real repository questions rather than
generic graph benchmarks. Candidate cases include known incidents, policy
migrations, rule supersessions, connector-schema changes, and claims already
supported by manually assembled evidence.

Useful measures include:

- **answer coverage** — fraction of golden questions with a useful result;
- **evidence coverage** — fraction of returned claims with immutable supporting
  or refuting evidence;
- **attribution precision** — fraction of `introduced-by`, `governed-by`, and
  semantic rule edges accepted by review;
- **historical reproducibility** — same pinned inputs yield the same established
  paths;
- **freshness honesty** — stale or incomplete sources are surfaced rather than
  silently omitted;
- **incident utility** — whether the dossier reduces time to a supported
  mitigation decision;
- **impact recall** — known downstream consumers appear in connector, symbol,
  and policy blast-radius results.

The graph earns normative promotion when its stable concepts are needed by more
than one consumer and conformance tests can distinguish correct implementations
from plausible but incompatible ones.

---

## 11. Normative-Gap Register

The informative design can be prototyped within existing contracts. The
following behaviors would trigger targeted amendments rather than a new
normative spec file:

| Contract | Amendment trigger |
|---|---|
| N1 | Governance graph, node/edge identity, temporal query semantics, or graph query becomes a stable Core concept |
| N3 | Graph projection, edge attestation, or claim lifecycle becomes observable runtime event data |
| N4 | Structured rule applicability, immutable rule revisions, or `extends` / `supersedes` / `contradicts` becomes policy-pack contract |
| N5 | A CLI, TUI, API, or dashboard graph-query surface becomes a supported interface |
| N6 | Claim, support edge, graph snapshot/delta, or dossier becomes a first-class artifact with conformance requirements |
| N9 | PR-to-symbol introduction or provider correlation becomes required external-PR behavior |

The likely first amendments are N1 for identity/boundary concepts and N6 for
claim provenance. N4 follows only after symbol-level applicability and rule
evolution semantics have been tested against real packs.

---

## 12. Failure and Security Posture

The graph introduces several failure modes that the first slice should expose
explicitly:

- **Stale projection:** result carries source pins and freshness watermarks.
- **Incomplete coverage:** missing symbol, PR, policy, or lineage coverage appears
  in the result.
- **Identity collision:** ambiguous aliases remain unresolved; they are not
  silently merged.
- **Heuristic laundering:** similarity and model classifications remain proposed
  until attested.
- **Historical rewrite:** immutable versions and execution pins preserve the
  rule, code, and evidence used at decision time.
- **Authority confusion:** graph presence never grants access or authorizes an
  effect.
- **Prompt injection:** extracted content is payload; it cannot mint attested
  edges, claims, labels, grants, or DecisionEnvelopes.
- **Circular support:** claim traversal detects cycles and reports them rather
  than treating recursion as additional confidence.
- **Evidence overstatement:** support, confidence, and truth remain distinct.

The graph query path is observational. Proposed graph mutations, rule changes,
knowledge promotion, remediation, or other effects cross ordinary governed
interfaces and receive their own decisions and receipts.

---

## 13. Non-Goals

The initial capability does not attempt to:

- replace git, policy packs, evidence bundles, the knowledge store, Data Foundry
  lineage, Codex, or Ariadne;
- create a universal enterprise ontology;
- use an LLM as the authority for causality, policy applicability, contradiction,
  or supersession;
- treat text similarity as semantic equivalence;
- infer authorization from documentation or projected edges;
- require RDF, OWL, SHACL, SPARQL, or a graph database;
- model every runtime event as a permanent graph node;
- expose an unrestricted cross-tenant traversal surface;
- make the first incident dossier an effectful remediation agent.

---

## 14. Open Questions

The experimental slice should resolve these before normative promotion:

1. Which logical identities remain stable across code moves and symbol renames?
2. Is bitemporal valid/observation time necessary for every edge, or only policy,
   decision, incident, and data-lineage edges?
3. Which `governed-by` relationships can be compiled deterministically from
   policy selectors, and which require attestation?
4. What evidence is sufficient to promote `changed-by` to the causal
   `introduced-by` relationship?
5. Should claims be their own N6 artifact or a typed assertion inside an
   evidence/dossier artifact?
6. Which rule relationships have deterministic semantics, and which always
   remain human- or authority-attested?
7. How should refuting evidence affect a claim without erasing its historical
   support path?
8. What is the smallest graph revision token that makes a dossier replayable?
9. Which queries belong in Core, and which remain Miniforge or Data Foundry
   product features?
10. Does a bounded RDF/SHACL or OWL adapter materially improve validation or
    interoperability after the native edge model is proven?

---

## 15. Adoption Path

```text
informative convergence model
        ↓
read-only repository-local projection
        ↓
incident + claim-provenance golden cases
        ↓
measured identity, temporal, and query semantics
        ↓
targeted N1 / N4 / N6 amendments
        ↓
stable production feature set
        ↓
optional interoperability and reasoner adapters
```

The first success criterion is not “a graph exists.” It is that Miniforge can
answer a consequential governance or incident question with a reproducible path
from conclusion to evidence, while saying clearly what it does not know.
