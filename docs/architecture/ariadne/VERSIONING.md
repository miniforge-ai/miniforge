<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Versioning Ariadne

Ariadne is a portable architecture. Systems adopt it, adapt it, and
fork it, and they need to be able to say precisely *which* Ariadne they
adopted — and to work out later what changed. This file defines how
that works.

## Tags are the unit of adoption

Every published version is a git tag in this repository:

```text
ariadne/vMAJOR.MINOR.PATCH
```

The `ariadne/` prefix keeps these separate from the engine's own
release tags (`v2026.MM.DD.N`) and stable markers (`stable/*`). A tag
points at a commit where the whole architecture unit — the spec, its
diagrams, and its explainers — is internally consistent.

**Cite the tag, never `main`.** An adoption document that links to
`main` describes a moving target, which defeats the point of recording
an adoption at all.

## What a version bump means

Semantic versioning, with the compatibility surface defined below in
architecture terms rather than API terms.

| Bump | Meaning for an adopter | Examples |
|---|---|---|
| **MAJOR** | A fork must re-derive. Something an implementation was entitled to rely on has changed. | An axiom (A1–A3) changes; one of the six frozen interfaces (§13) changes shape incompatibly; a decision rule inverts (a default that permitted now denies); sections are renumbered. |
| **MINOR** | A fork can adopt additively. Existing derivations stay valid. | A new mechanism or section; a new optional clause field; a new obligation type or reason code; a worked example added; adopter-specific material removed. |
| **PATCH** | Nothing to adopt. | Clarification, typo, prose tightening, a diagram redrawn without a semantic change. |

## The compatibility surface — what a MAJOR bump protects

These are the things an implementation is entitled to build against.
They do not change without a major bump:

1. **The axioms.** A1 people are tenants; A2 exactly one
   `owner_tenant_id` per record; A3 membership is a relation, never
   containment.
2. **The six frozen interfaces** (§13): `PolicyClause`,
   `PolicyTransform`, `DestinationDescriptor`,
   `ExecutionGrant`/`Delegation`, `DecisionEnvelope`,
   `ProposedTransaction`.
3. **Section numbering.** Adoption documents, code comments, and
   review threads cite sections by number. Renumbering silently
   invalidates every one of those citations, so it is a breaking
   change — which is why §10 is an empty placeholder rather than a
   reclaimed number.
4. **The clause and decision vocabularies** — the enforcement actions
   and their relaxation semantics, and the decision values an envelope
   can carry.

Everything else — prose, rationale, defense material, worked examples,
diagrams — can change in a minor or patch release.

## Forking

The intended workflow for a system that adapts Ariadne rather than
adopting it verbatim:

1. **Fork at a tag.** Record the tag in your own adoption document
   (`adopted: ariadne/v1.6.0`), not the date you copied it.
2. **Keep your deltas separate and named.** A fork is best described
   as "Ariadne v1.6.0 plus these deltas", so the deltas stay legible
   as the upstream moves.
3. **Diff tags to see what to adopt.** To review what changed between
   the version you forked and a later one:

   ```bash
   git diff ariadne/v1.6.0..ariadne/v1.7.0 -- docs/architecture/ariadne/
   ```

   The changelog states the bump type, which tells you whether that
   diff is something you can take additively or something that
   requires re-deriving.
4. **Say which version you mean.** "Our fork of Ariadne v1.6.0" is a
   statement someone can verify. "Our version of Ariadne" is not.

## Releasing a new version

1. Land the content change on `main` through the normal review process.
2. Add a `CHANGELOG.md` entry stating the bump type and, for anything
   above PATCH, what an adopter has to do about it.
3. Update the `**Version:**` line at the top of the spec.
4. Tag the merge commit and push the tag:

   ```bash
   git tag -a ariadne/vX.Y.Z -m "Ariadne vX.Y.Z"
   git push origin ariadne/vX.Y.Z
   ```

Tags are never moved or deleted once pushed. A published version that
turns out to be wrong is superseded by the next version, not rewritten
— an adopter who forked from it needs it to keep existing.
