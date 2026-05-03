# Generalized Classification Runtime

> **Status:** informative — architectural design spec. Captures the
> classification-runtime direction agreed during the dependency-attribution
> review. Not yet expressed as a work-spec; conversion to
> `work/<id>.spec.edn` is a follow-up when the engine work is ready to be
> picked up.

## Context

While landing the dependency-attribution work, we reviewed the growing number of
"classifier" implementations in Miniforge and asked whether they should share a
common polymorphic abstraction.

The conclusion was:

- The different classifiers do **not** all share the same domain model.
- They **do** appear to share the same **execution model**.
- The right abstraction is therefore **not** "many classifier objects behind a
  protocol" as the first move.
- The right abstraction is a **single generalized, rule-driven classification
  runtime** that is configured differently per classification domain.

In other words:

- shared engine
- config as data
- domain-specific rule packs
- domain-specific output schemas

## What We Observed

There are already several classifier-like paths in the codebase:

- `failure-classifier`
- `agent-runtime` error classification
- `pr-lifecycle` comment / triage classification
- likely future behavioral / gate / workflow recommendation classification paths

These systems look similar because they all:

- inspect structured or semi-structured input
- apply ordered rules
- assign a classification
- sometimes derive retryability, severity, or next-action guidance

But they are not all the same business concept:

- failure classification is not PR comment triage
- PR comment triage is not behavioral violation classification
- gate classification is not provider/platform failure attribution

So the shared layer is the **classification runtime**, not a shared universal
domain result type.

## Architectural Conclusion

We should introduce:

- **one generalized classification runtime**
- **rule packs loaded from EDN/config data**
- **domain adapters** that define:
  - input projection
  - rule pack
  - output construction
  - boundary schema

That gives us:

- one engine
- many domains
- no forced universal result map where it does not fit

## Why Not Protocols First

A protocol-first design is not the right starting point here because:

- most of these classifiers are pure, data-driven transformations
- the main duplication is in rule evaluation and normalization, not in runtime
  object behavior
- protocols would add indirection before we have a stable shared execution
  contract
- the immediate need is to consolidate rule execution, precedence, fallback, and
  provenance

Protocols may still make sense later if we discover genuinely swappable engines,
but that is not the current design pressure.

## Preferred Shape

### Shared Engine Responsibilities

The generalized classification runtime should own:

- rule loading
- rule schema validation
- ordered rule evaluation
- matching strategies
  - regex
  - predicate
  - exact-value
  - exception-class
  - anomaly-category
  - other explicit strategies as needed
- precedence
- fallback/default classification
- normalized match result
- explanation/provenance of which rule matched

### Domain Adapter Responsibilities

Each classification domain should provide:

- input projection
- rule-pack location/config
- domain output constructor
- output schema
- optional domain-specific post-processing

### Important Constraint

Do **not** force all domains into one giant universal result shape.

What should be shared:

- engine mechanics
- rule evaluation semantics
- match provenance model

What may remain domain-specific:

- output fields
- result schemas
- downstream interpretation

## Immediate Fit in Miniforge

### Strong Candidate for First Migration

- `failure-classifier`

Why:

- already rule-driven
- already being centralized
- directly relevant to the dependency-attribution stack in progress
- offers a good proof point for the generic engine

### Strong Candidate for Early Follow-on

- `agent-runtime` error classification

Why:

- currently duplicates pattern/rule ideas
- should likely delegate to `failure-classifier` or the new generic engine
  rather than maintain a sibling classification architecture

### Possible Later Candidates

- `pr-lifecycle` comment triage
- behavioral violation classification
- gate classification
- workflow recommendation or workflow-family selection logic

These should only migrate if they genuinely fit the generic rule-driven model.

## Relationship to Current Dependency-Attribution Work

This is an **isolated sidecar track**.

It should not block the current dependency-health stack:

1. dependency attribution taxonomy
2. classifier integration
3. reliability + supervisory-state health projection
4. dependency events + degradation integration
5. CLI/dashboard attribution
6. workflow/evidence/observe integration

The new classification-runtime effort can proceed in a separate worktree while
the current stack continues.

## Proposed New Spec

Suggested spec file:

- `work/generalized-classification-runtime.spec.edn`

### Suggested Purpose

Define a reusable, rule-driven classification runtime for Miniforge that:

- executes classification from data
- supports multiple classification domains
- preserves domain-specific output contracts
- eliminates duplicated bespoke pattern-matching logic

## Proposed Spec Scope

### 1. Core Runtime

Define the generalized classification engine:

- inputs
- rule evaluation lifecycle
- precedence semantics
- fallback behavior
- normalized match result

### 2. Rule Pack Contract

Define config-as-data rule packs:

- EDN structure
- required and optional keys
- supported matcher kinds
- priority / order semantics
- domain metadata

### 3. Boundary Schemas

Define schemas for:

- rule packs
- normalized engine match results
- domain adapter inputs
- domain adapter outputs

### 4. Provenance / Explainability

Define how the engine reports:

- matched rule id
- matched pack/domain
- matched strategy
- any relevant evidence fragment

This is important for operator trust, debugging, and later dashboarding.

### 5. Failure-Classifier Migration

Make `failure-classifier` the first concrete domain migrated onto the generic
runtime.

### 6. Agent-Runtime Cleanup

Evaluate and likely migrate `agent-runtime` classification so it stops owning a
parallel bespoke rule-execution path.

### 7. Candidate Domain Review

Assess whether other classifier domains should adopt the engine:

- PR triage
- behavioral classification
- gate classification

Not all of them must migrate immediately.

## Proposed Implementation DAG

### PR 1. Core Classification Engine

- create lower-level `classification` component
- implement generic runtime
- add rule-pack schema
- add normalized match/explanation shape

### PR 2. Failure-Classifier on the Engine

- migrate `failure-classifier`
- preserve existing public contract where possible
- prove engine viability on a real domain

### PR 3. Agent-Runtime Delegation Cleanup

- remove duplicate rule-evaluation logic from `agent-runtime`
- delegate to the new engine / failure-classifier domain pack

### PR 4. Candidate Domain Evaluation

- test fit for PR triage / behavioral / gate classification
- only migrate domains that actually benefit from the engine

### PR 5. Duplication Closeout

- remove bespoke pattern-match implementations that the new engine supersedes
- tighten docs/tests around the canonical classification architecture

## Design Guardrails

- stratified design must hold
- config is data, not code
- no magic rule semantics hidden in orchestration
- boundary schemas only; do not over-validate deep internal pure functions
- keep engine generic, but keep domain meaning explicit
- prefer pure pipeline functions over unnecessary polymorphism

## Summary

The key insight is:

> We do not have one universal classification domain, but we likely do have one
> generalized classification execution model.

So the next architecture move is:

- build a generic rule-driven classification runtime
- keep domain rule packs and result schemas distinct
- migrate `failure-classifier` first
- evaluate additional classifier domains afterward
