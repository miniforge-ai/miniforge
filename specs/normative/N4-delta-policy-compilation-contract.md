<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Normative Spec Extension: Policy Candidate Model and Pack Compilation Contract

## Purpose

Define the OSS-stable contract for turning source policy material into reviewable, provenance-backed policy artifacts.

This spec standardizes the intermediate representation, provenance requirements, approval lifecycle, and compilation
guarantees. It does **not** standardize source crawling, connector orchestration, or centralized governance workflows.

## Why this exists

"Compile policy packs from organizational documents" is strategically correct, but the stable architectural boundary is
not the ingestion UX or the current compiler implementation.

The stable boundary is the contract between source policy material and usable policy packs.

## Relationship to other specs

- `policy-pack-extensibility.spec.*`  
  Defines the pack schema targeted by this compilation contract.
- `policy-pack-configuration.spec.*`  
  Defines how compiled packs are attached to repos and applied at runtime.
- `repo-config-profile.spec.*`  
  Declares repo-local pack references and attachment semantics.
- `policy-pack-compilation.spec.*`  
  Work spec for current compile / derive / compose implementation and UX. This normative extension constrains that work
  spec.

## Spec metadata

- **Title:** Policy Candidate Model and Pack Compilation Contract
- **Type:** Normative
- **Scope:** Open-source policy origination substrate

## Description

Standardize the open-source contract for originating policy packs from source material. A policy compiler may ingest
explicit policy documents, repository artifacts, or manually authored content, but it must normalize extracted policy
into a reviewable candidate representation before promotion into a compiled policy pack.

This spec defines:

- the intermediate policy-candidate representation
- required provenance and lineage metadata
- candidate origin types and enforceability classes
- candidate lifecycle states and promotion rules
- compilation guarantees for produced packs

This spec does **not** define:

- source connector catalogs
- crawling or indexing infrastructure
- central approval queues or reviewer routing
- hosted pack registries or policy distribution services

## Principles

### 1. Policy origination is pluggable

Policy packs may originate from multiple producer types, but all producers must converge on the same candidate and pack
contracts.

### 2. Policy review precedes authority

Extracted or inferred policy must exist as reviewable candidate data before it is treated as authoritative pack content.

### 3. Provenance is foundational

Every compiled rule must retain provenance sufficient to trace the rule to its originating source material or authoring
action.

### 4. Pack portability

A compiled pack must remain portable across environments without dependency on external infrastructure.

### 5. Composition is deterministic

Composition and precedence resolution over compiled packs must be deterministic and must not require LLM participation.

## Definitions

### Producer types

- `manual`
- `document-compile`
- `repo-derive`
- `pack-compose`
- `import`

### Candidate origin types

- `direct`
- `inferred`
- `composed`
- `manual`

### Candidate enforceability classes

- `executable`
- `heuristic`
- `advisory`
- `human-review`

### Candidate lifecycle states

- `proposed`
- `accepted`
- `rejected`
- `overridden`
- `deprecated`

### Source types

- `file`
- `url`
- `repo-artifact`
- `pack`
- `manual`

### Provenance support types

- `stated`
- `derived`
- `composed`
- `authored`

## Candidate schema

A compiler must emit a candidate representation before authoritative pack promotion.

### Required fields

#### `candidate/id`

Stable identifier for the candidate rule within the candidate set.

#### `candidate/statement`

Normalized policy statement in concise natural language.

#### `candidate/category`

Canonical policy category used for grouping and pack layout.

#### `candidate/origin-type`

One of `direct`, `inferred`, `composed`, or `manual`.

#### `candidate/enforceability`

One of `executable`, `heuristic`, `advisory`, or `human-review`.

#### `candidate/status`

Lifecycle state. Initial extraction must begin as `proposed` unless the candidate is manually authored as accepted.

#### `candidate/provenance`

Non-empty list of provenance records supporting the candidate.

### Optional fields

#### `candidate/severity`

Suggested enforcement severity when applicable.

#### `candidate/applicability`

Structured applicability constraints such as repo type, language, environment, or path scope.

#### `candidate/check-hint`

Optional machine-oriented hint, pattern, or expression for later executable realization.

#### `candidate/confidence`

Producer-reported confidence. Useful for review prioritization, but must not substitute for provenance.

#### `candidate/notes`

Freeform explanatory notes from the producer or reviewer.

#### `candidate/review`

Structured review metadata such as reviewer, timestamp, decision rationale, or edit history.

## Provenance contract

### Requirements

1. Each candidate must include one or more provenance records.
2. Each provenance record must identify the source type and a stable source reference.
3. Each provenance record should identify a locator sufficient to re-open the relevant section, file region, or source
  object.
4. Each provenance record must declare whether support is `stated`, `derived`, `composed`, or `authored`.
5. Compiled rules must retain provenance from their accepted candidate ancestors.
6. If a candidate is synthesized from multiple sources, provenance must preserve all materially contributing source
  records rather than collapsing to one source pointer.

### Provenance record shape

#### `provenance/source-type`

Source kind such as `file`, `url`, `repo-artifact`, `pack`, or `manual`.

#### `provenance/source-ref`

Stable reference to the source object, path, URL, artifact key, or authored context.

#### `provenance/locator`

Optional structured locator, for example section heading, line span, AST node path, or config key.

#### `provenance/support-type`

One of `stated`, `derived`, `composed`, or `authored`.

#### `provenance/source-fingerprint`

Fingerprint of the relevant source material or source object version.

#### `provenance/excerpt-hash`

Optional hash of the precise supporting excerpt or artifact fragment.

#### `provenance/compiler`

Optional producer or compiler identity and version.

## Promotion rules

1. Only `accepted` candidates may be promoted into authoritative compiled pack rules.
2. A compiler may emit proposed candidate artifacts without emitting a final pack.
3. A candidate with origin type `inferred` should default to stronger review scrutiny than a directly stated candidate.
4. A candidate with enforceability `human-review` or `advisory` must not be silently upgraded to `executable` during
  compilation.
5. Promotion from candidate to compiled rule may transform field names to the pack schema, but must preserve semantic
  meaning and provenance.
6. If multiple accepted candidates collapse into one compiled rule, the resulting rule must retain merged provenance and
  an auditable mapping back to all contributing candidates.

## Compilation guarantees

1. A produced pack must conform to the existing `.pack.edn` schema.
2. A produced pack must include pack-level metadata declaring producer type, compilation timestamp, and source
  fingerprint set.
3. A produced pack must preserve provenance for each compiled rule.
4. Compilation must be schema-stable: repeated runs over unchanged source material and identical compiler configuration
  must produce semantically equivalent candidate sets modulo ordering and bounded confidence jitter.
5. Compose mode must be pure and deterministic.
6. Derive mode must support operation without LLM participation for artifact classes that are directly machine-readable.
7. The compiler must be able to emit candidate artifacts and compiled pack artifacts as separate outputs.

## Non-goals

- This spec does not require that all candidates become executable checks.
- This spec does not require source connectors or centralized indexing.
- This spec does not define review queues, waivers, or distribution workflows.
- This spec does not define UI shape for candidate review.

## Acceptance criteria

1. The compiler can emit a reviewable candidate artifact containing required candidate and provenance fields before
  final pack emission.
2. Accepted candidates compile into a valid `.pack.edn` while preserving provenance.
3. A derived candidate from repo artifacts is distinguishable from a directly stated candidate in metadata.
4. Composed rules preserve contributing provenance from upstream packs or candidates.
5. The same runtime pack consumer can consume both manually authored packs and compiled packs without special handling.

## Design notes

The important architectural move is that policy origination is separated from policy consumption.

- **Origination** covers extraction, normalization, review, and promotion.
- **Consumption** covers pack attachment, precedence, and runtime policy evaluation.

This spec standardizes origination outputs that are portable into the existing pack system.

## Positioning note

The interesting claim is not that an LLM can turn docs into rules.

The interesting claim is that miniforge policy becomes a compilable, provenance-backed artifact that can be derived from
organizational reality and attached to repos through stable config and pack semantics.
