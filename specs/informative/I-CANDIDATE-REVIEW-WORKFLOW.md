# Supporting Note: Candidate Review Workflow

## Purpose

Define the review workflow that sits between policy-candidate extraction and authoritative pack publication.

This note operationalizes the OSS policy-candidate contract without changing that contract. It describes how candidate
sets should be reviewed, promoted, and recorded so that compiled policy packs remain trustworthy.

## Why this exists

The compiler should accelerate policy origination, not replace judgment.

Without an explicit review workflow, extracted policy candidates become either:

- untrustworthy because they are accepted too easily, or
- unusable because they require ad hoc manual interpretation each time.

The review workflow gives miniforge a stable midpoint between raw extraction and authoritative enforcement.

## Relationship to other specs

- `policy-pack-compilation-contract.spec.md`
  Defines the candidate model, provenance requirements, and promotion rules.
- `policy-pack-compilation.spec.*`
  Defines the current compile / derive implementation and UX.
- `pack-attachment-and-precedence.md`
  Defines how accepted packs are attached to repos and how conflicts are resolved once packs exist.

## Scope

This note covers:

- candidate review stages
- review decisions and state transitions
- minimum evidence required for promotion
- special handling for inferred and human-review candidates
- review outputs and auditability

This note does not cover:

- reviewer routing and approval queue infrastructure
- centralized governance workflows
- connector-specific extraction logic

## High-level model

A policy compiler produces a **candidate set**.
A reviewer or review process turns that candidate set into an **accepted, rejected, or deferred decision set**.
Only the accepted subset is promoted into an authoritative pack.

## Review artifact set

A complete review bundle should contain the following artifacts.

### 1. Candidate set

Structured candidate records emitted by the compiler.

### 2. Source manifest

A manifest of the source materials consulted during compilation, including stable references and source fingerprints.

### 3. Compiler manifest

The compiler identity, version, relevant mode (`compile`, `derive`, `compose`), and relevant configuration.

### 4. Review decisions

A structured record of accept, reject, defer, edit, and merge decisions.

### 5. Promotion output

The resulting `.pack.edn` artifact or an explicit record that promotion was not completed.

## Review stages

### Stage 1. Intake

Validate that the candidate set is structurally complete.

Minimum checks:

- every candidate has an identifier
- every candidate has provenance
- source fingerprints are present
- candidate status is initialized correctly
- origin and enforceability classes are populated

Candidates that fail intake should not proceed to substantive review.

### Stage 2. Triage

Group and prioritize candidates for review.

Recommended triage dimensions:

- `origin-type`
- `enforceability`
- `severity`
- `category`
- source confidence or support density
- similarity / near-duplicate clustering

Suggested triage defaults:

- `direct` + `executable` candidates first
- `inferred` candidates second
- `human-review` and `advisory` candidates grouped for semantic review

### Stage 3. Substantive review

Reviewers evaluate whether the candidate reflects an actual organizational policy obligation.

The key questions are:

1. Is the policy statement faithful to the source material?
2. Is the candidate scoped correctly?
3. Is the candidate duplicative of another accepted rule?
4. Is the enforceability classification correct?
5. Is the candidate ready for pack promotion, or does it need editing or deferral?

### Stage 4. Decision

Each candidate should end the review pass with one of the following outcomes:

- `accepted`
- `rejected`
- `deferred`
- `edited-then-accepted`
- `merged-into-other`

`deferred` is operationally useful even if the core candidate lifecycle model later maps it to `proposed` or another
house status.

### Stage 5. Promotion

Accepted candidates are transformed into authoritative pack rules.

Promotion must:

- preserve provenance
- preserve origin semantics
- preserve review metadata or an auditable pointer to it
- avoid silently strengthening a rule’s enforceability class

## Decision guidance

### Accept

Accept a candidate when:

- the obligation is actually supported by source material or explicit authoring intent
- provenance is sufficient to re-open the supporting source
- scope and category are materially correct
- the rule does not duplicate a stronger accepted rule

### Reject

Reject a candidate when:

- the source does not actually support the obligation
- the candidate overstates or distorts the source
- provenance is too weak to validate
- the candidate is noise, duplication, or a transient convention that should not be authoritative policy

### Edit before acceptance

Edit a candidate when:

- the obligation is real but the wording is sloppy
- applicability constraints are missing or too broad
- category or severity is materially wrong
- multiple candidates should be normalized into one canonical rule

### Defer

Defer a candidate when:

- the source appears relevant but is ambiguous
- another authoritative policy source is expected to supersede it
- the rule is probably real but requires an owner or domain expert to confirm it

## Review guidance by origin type

### `direct`

Review for faithfulness, scope, and duplication.
This is the lowest-friction class.

### `inferred`

Review with higher scrutiny.

Expected checks:

- verify that the inference is not merely a local habit
- verify that the repo convention is intended to be policy rather than incidental implementation
- prefer narrower applicability unless there is strong evidence of generality

### `composed`

Review mainly for conflict behavior and inherited semantics.

Expected checks:

- no contradictory inherited obligations
- precedence and severity rules remain coherent
- inherited provenance remains visible

### `manual`

Review mainly for authoritativeness and placement.

Expected checks:

- proper ownership
- correct category and scope
- explicit rationale when authoring supersedes extracted material

## Review guidance by enforceability class

### `executable`

Verify that the obligation is crisp enough to support machine checking.
Do not accept false precision.

### `heuristic`

Verify that the rule is still useful even if implementation will remain approximate.

### `advisory`

Verify that the rule belongs in a pack as non-blocking guidance rather than as a hard requirement.

### `human-review`

Verify that the rule is framed for reviewer judgment and not mislabeled as machine-enforceable.

## Minimum promotion criteria

A candidate should not be promoted unless all of the following are true:

1. provenance is present and materially sufficient
2. scope is acceptable
3. lifecycle decision is explicit
4. duplicate handling is complete
5. the resulting pack rule has a stable identifier
6. the resulting pack rule retains a traceable mapping to the reviewed candidate

## Review metadata

Review systems should preserve at least:

- reviewer identity or reviewer role
- review timestamp
- decision outcome
- rationale
- edits made during normalization
- merge or supersession references where applicable

This may be local metadata, comments, or a simple sidecar artifact.

## Review workflow

A practical review loop should look like this:

1. compile or derive candidates from explicit inputs
2. open a review surface showing candidate statement, provenance, origin, and enforceability
3. accept, reject, edit, merge, or defer candidates
4. emit a reviewed candidate artifact
5. promote accepted candidates into a pack
6. commit both the pack and the reviewed-candidate artifact

## Recommended outputs

For repo use, the following outputs are recommended:

- `policy-candidates.edn`
- `policy-candidate-review.edn`
- `*.pack.edn`

Even if the normative specification remains in markdown, the review artifacts themselves should be structured and
machine-readable.

## High-level rule

Extraction proposes.
Review decides.
Promotion authorizes.

## Non-goals

This note does not require a single fixed UI or a single mandatory reviewer model. The important invariant is that
compiled policy packs come from explicit, auditable review decisions rather than silent one-shot extraction.
