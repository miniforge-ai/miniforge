<!--
  Title: Governance Provenance Graph informative specification
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# spec: define the Governance Provenance Graph convergence model

## Overview

Adds an informative specification for a versioned, evidence-bearing projection
across code, specifications, policy, decisions, incidents, claims, and data
lineage. The document is indexed in `SPEC_INDEX.md`; it adds no runtime code or
normative contract.

## Motivation

Miniforge already has graph-shaped repository intelligence, workflow DAGs,
knowledge links, Codex reachability, Ariadne authority relations, evidence
provenance, and Data Foundry lineage. Cross-cutting incident and governance
questions still require manual joins across those sources.

The convergence model defines how to answer those questions without creating a
new authority store or prematurely committing Core to OWL, RDF, SHACL, a graph
database, or a public graph protocol.

## Changes

- Add `I-GOVERNANCE-PROVENANCE-GRAPH.md` with provisional node, edge, temporal,
  and claim-provenance semantics.
- Define canonical query paths for incident attribution, governing policy,
  rule evolution, connector impact, and transitive claim support.
- Bound the first experiment to a read-only repository-local projection and an
  advisory incident dossier.
- Record the evidence required before targeted N1, N3, N4, N5, N6, or N9
  amendments.
- Register the document in the informative architecture section of the
  specification index and refresh the index date.

## Testing Plan

Documentation-only change. Validate with `markdownlint`, whitespace checks,
the PR line-budget gate, and adversarial review for accidental RFC 2119
obligations or authority-boundary changes.

## Deployment Plan

Merge only; no runtime behavior changes. Follow-up work should specify and
measure the read-only `Incident → Symbol → Commit → PR` experimental slice
before promoting stable concepts into normative contracts.

## Checklist

- [x] Informative status and non-goals are explicit
- [x] Existing authoritative sources remain authoritative
- [x] Heuristic edges remain proposed until attested
- [x] Claim support and refutation preserve immutable evidence paths
- [x] Normative amendment triggers are recorded
- [x] Specification index updated
- [ ] Review and merge
