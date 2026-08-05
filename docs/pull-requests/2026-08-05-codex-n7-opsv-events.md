<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add canonical OPSV events

## Overview

Implements the complete nine-event N3 OPSV family as decomposed schemas and
public constructors, with every event correlated to its preallocated N6 evidence
bundle identifier.

## Motivation

OPSV workflow and evidence assembly require one canonical durable event surface
before execution phases can be wired safely.

## Layer

Infrastructure — event contracts and pure envelope constructors.

## Depends on

- #1650 — merged

## Changes in Detail

- Add exact schemas for all nine N3 section 3.14 event types.
- Add public constructors that preserve workflow, evidence, and tenant identity.
- Register all serialized event names in the authoritative registry.
- Decompose OPSV schemas and public API into focused namespaces.
- Refresh stale registry source/date/count documentation found during audit.

## Testing Plan

- Validate every canonical event and required preallocated evidence identifier.
- Validate every constructor against its corresponding schema.
- Run the complete event-stream brick suite in every composed project.
- Run pre-commit and the event-stream brick in every project composition.
- Confirm kondo and stratum lint are clean for every changed Clojure file.

## Deployment Plan

No migration is required; this adds new event types and constructors.

## Checklist

- [x] Nine schemas match N3 section 3.14
- [x] Nine constructors preserve N6 correlation
- [x] Event registry includes all serialized names
- [x] New focused tests: 3 tests, 66 assertions, 0 failures/errors
- [x] Full event-stream brick suite passes in all project compositions
- [x] Pre-commit suite passes
- [x] Changed Clojure files have zero kondo or stratum findings

The whole event-stream component scan continues to report eleven pre-existing
`SL003` namespace-depth findings outside this diff. This PR removes the finding
that modifying the registry would otherwise have introduced by extracting its
data-loading and audit-summary responsibilities.
