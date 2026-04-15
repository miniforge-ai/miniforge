# Companion Note: Pack Attachment and Precedence

## Purpose

Define how compiled policy packs are attached to repositories and how multiple applicable packs are resolved into an
effective policy set.

This note exists to keep policy origination separate from policy application. Compiling packs is one concern.
Determining which packs apply, and what wins when they disagree, is another.

## Why this exists

Once miniforge can compile packs from organizational material, the next failure mode is not extraction quality. It is
ambiguity about which packs apply where.

A repo needs deterministic answers to questions like:

- which packs are in force
- whether org policy overrides repo policy
- whether local teams can narrow or soften inherited requirements
- how duplicates and conflicts are resolved

Without a crisp attachment and precedence model, policy packs become hard to trust operationally.

## Relationship to other specs

- `policy-pack-compilation-contract.spec.md`
  Defines how candidates become authoritative packs.
- `policy-pack-extensibility.spec.*`
  Defines pack structure and extensibility points.
- `policy-pack-configuration.spec.*`
  Defines config-level references to packs.
- `repo-config-profile.spec.*`
  Defines repository-local profile/config shape.
- `candidate-review-workflow.md`
  Defines how a candidate becomes accepted before it exists as a pack rule.

## Scope

This note covers:

- pack attachment points
- effective policy-set construction
- precedence ordering
- override limits
- duplicate and conflict handling

This note does not cover:

- how a pack is compiled
- enterprise registry/distribution mechanics
- approval workflow for new packs

## Conceptual model

There are two distinct questions.

### Question 1. Which packs apply?

This is the **attachment** problem.

### Question 2. Given the applicable packs, what is the final rule set?

This is the **precedence and composition** problem.

Both must be deterministic.

## Attachment model

A repository should be able to declare applicable packs explicitly in repo configuration.

Recommended attachment sources, from broadest to narrowest, are:

1. platform or org default packs
2. business-unit or domain packs
3. repo profile packs
4. repo-local explicit packs
5. local developer overlays, if supported

Not every environment needs every layer, but the conceptual ladder should remain stable.

## Recommended config shape

The exact schema belongs in the configuration specs, but the conceptual form should be equivalent to:

```edn
{:policy/attachments
 [{:pack/id :org.core-security
   :source  :org-default}
  {:pack/id :org.kubernetes-service
   :source  :profile}
  {:pack/id :repo.payments-exceptions
   :source  :repo-local}]}
```

The critical point is that attachment metadata should preserve where each pack came from.

## Effective policy-set construction

An evaluator should construct the effective policy set in these broad phases.

### Phase 1. Resolve attached packs

Resolve all pack references declared by the applicable attachment layers.

### Phase 2. Normalize pack identities and versions

Ensure that pack identity, version, and provenance are resolved before rule composition begins.

### Phase 3. Expand inherited or composed packs

If packs compose other packs, the full dependency graph must be expanded before effective precedence is calculated.

### Phase 4. Apply precedence rules

Resolve collisions and overrides deterministically.

### Phase 5. Emit effective policy set

Produce the final rule universe that runtime workflows and agents actually consult.

## Precedence model

Recommended default precedence, from strongest to weakest, is:

1. non-overridable platform or org rules
2. org default packs
3. domain or profile packs
4. repo-local packs
5. advisory local overlays

This default keeps the enterprise story sane while still allowing repository specialization.

## Override rules

### 1. Hard constraints may not be weakened locally

A rule marked non-overridable must not be downgraded, disabled, or shadowed by narrower layers.

### 2. Narrowing is safer than weakening

A narrower-scoped local rule may add additional obligations for a path, environment, or component.
That is usually acceptable.

### 3. Severity downgrades should be explicit

If a lower layer is allowed to downgrade severity, it should require explicit override metadata and rationale.
Silent downgrades are a bad default.

### 4. Advisory rules are easier to override

`advisory` rules may be replaced or suppressed more freely than `executable` or `heuristic` rules, provided the change
remains auditable.

## Duplicate handling

Duplicate rules should not simply accumulate.

A composition engine should attempt to classify duplicates as one of:

- exact duplicate
- same obligation, different wording
- same identifier, different semantics
- conflicting obligations

Suggested handling:

- exact duplicates collapse into one rule with merged provenance
- semantically equivalent duplicates normalize to the stronger canonical rule
- same identifier with different semantics is a composition error
- genuinely conflicting obligations require explicit precedence or reviewer intervention

## Conflict handling

A conflict exists when two applicable rules cannot both be satisfied as written, or when they impose contradictory
severities or allowed behaviors.

When conflicts arise:

1. prefer the higher-precedence rule
2. preserve the lower-precedence rule in provenance and explainability output
3. require explicit reviewer acknowledgement if a lower layer attempted to weaken a higher-layer hard constraint
4. emit a composition warning or error when semantics are materially contradictory

## Provenance through composition

The effective rule set must preserve lineage.

For each effective rule, the system should be able to answer:

- which pack introduced it
- which attachment layer brought that pack into scope
- whether another rule was merged, shadowed, overridden, or rejected during composition

This matters for both explainability and audit.

## Repo-local exceptions

Repo-local exception packs are valid, but they should be obvious.

Recommended practice:

- isolate exceptions in a clearly named exception pack
- require rationale metadata
- prefer narrow applicability constraints
- avoid mixing standing org policy with repo-local exceptions in the same pack

This keeps exceptions visible instead of smearing them into the main policy surface.

## Local OSS expectations

In OSS, a user should be able to:

1. attach packs in repo config
2. inspect the resolved effective pack list
3. inspect the effective final rule set
4. see why a rule is in force
5. see which rule won when there was a conflict

If those are not inspectable locally, the pack model will feel opaque.

## Fleet implications

Fleet may extend this with:

- organization-wide pack registries
- recommended pack assignment by repo type
- centralized exception workflows
- waiver expiry and ownership
- org-wide impact analysis for pack changes
- compliance dashboards built from effective rule sets

Those features depend on the same attachment and precedence semantics; they should not invent a second model.

## Recommended outputs

Useful derived outputs include:

- resolved pack attachment manifest
- effective rule set manifest
- conflict report
- override report
- provenance trace for each effective rule

## High-level rule

Pack origination may be flexible.
Pack application must be deterministic.

## Suggested next step

If these semantics are not already explicit in `policy-pack-configuration.spec.*` or `repo-config-profile.spec.*`, fold
the stable parts of this note into one of those normative specs.
