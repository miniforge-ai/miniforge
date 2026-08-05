<!--
  Title: Read-only incident provenance projection
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add the read-only incident provenance projection

## Overview

Implements the first experimental slice from
`I-GOVERNANCE-PROVENANCE-GRAPH.md`: a repository-local, read-only incident
dossier connecting an operator-selected symbol or immutable range to blamed
commits, locally visible merge PRs, explicit specification mappings, applicable
policy rules, generated attribution claims, and supporting evidence.

## Motivation

Incident attribution currently requires separate searches across source,
history, PRs, specifications, and policy. This component creates one pinned,
evidence-bearing projection while preserving the distinction between a commit
that changed implicated lines and the stronger causal claim that it introduced
an incident.

## Changes

- Add the `governance-provenance` component and its public `build-dossier` API.
- Pin the requested repository revision before collecting any facts.
- Use read-only git blame, commit metadata, blob identity, and merge ancestry.
- Accept N1 symbol identities when available; otherwise project an immutable
  range and report missing symbol coverage.
- Apply explicit specification mappings and N4 policy file-glob applicability.
- Emit evidence-bearing `changed-by`, `contained-in`, `governed-by`, `supports`,
  `cites`, and `implicates` edges.
- Preserve distinct range evidence and specification clauses when graph edges
  converge on the same symbol, commit, or specification revision.
- Report incomplete coverage and unversioned governance inputs as gaps.
- Register the experimental component in the Miniforge project and development
  classpaths.
- Record implementation status in the informative convergence specification.

## Deliberate Boundaries

- No `introduced-by` edge or causal conclusion.
- No remote provider query, graph database, public graph protocol, or CLI.
- No inferred specification mapping or symbol identity.
- No effect, remediation, policy mutation, or authority decision.
- Local merge ancestry is labeled as its source and may remain unresolved.

## Testing Plan

- Complete symbol/range/commit/PR/spec/rule/evidence projection.
- Partial projection with explicit missing-source gaps.
- Unsafe repository-relative path rejection before git execution.
- Fail-closed behavior when the repository revision cannot be pinned.
- Unversioned specification and policy inputs remain visible gaps.
- Dogfood against this repository's real history.
- Full repository pre-commit suite before push.

## Dogfood Result

An incident location at `repo-index/interface.clj` resolved from its immutable
blob through commit `cdb1034` to local merge PR #327. With no symbol index,
specification mapping, or policy rules supplied, the dossier returned `:partial`
and named those three gaps instead of presenting the neighborhood as complete.

## Deployment Plan

Merge only. The API remains an experimental Miniforge component. A later slice
can add a provider adapter or stable UI only after the local evidence and
coverage semantics have been evaluated against incident golden cases.

## Checklist

- [x] Read-only source access
- [x] Repository revision pinned
- [x] Evidence-bearing derived edges
- [x] Unknown and incomplete coverage surfaced
- [x] No causal or authority escalation
- [x] Focused tests and real-repository dogfood
- [ ] Review and merge
