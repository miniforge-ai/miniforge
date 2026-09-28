<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Define governed OPSV effect disposition events

## Overview

Add the canonical N3 event for durable OPSV actuation dispositions, including
failure and uncertainty. This contract precedes the runtime audit producer.

## Motivation

The existing actuation event describes successful phase output. Audit consumers
also need proposed, failed and unknown effects joined to their authority and evidence.

## Layer and dependencies

Domain event contract in `event-stream`, using the public DecisionEnvelope and
EffectTransaction schemas. No provider or application dependency is introduced.
Data Foundry declares the transitive components required by Polylith.

## Changes in detail

- Register `:opsv.actuation/disposition` and its public constructor.
- Reuse the canonical effect states, envelope schema and governed-effect triple.
- Reject mismatched envelope identities and invalid effect states.
- Document the event in N3 and clear the document's existing prose-lint findings.
- Preserve normative requirements while splitting overlong prose sentences.

## Testing plan

Run the event-stream suites in consuming projects, Polylith checks, standards scan,
kondo, strata, prose lint and commit hooks. Constructor tests cover all OPSV event
types and reject invalid disposition payloads. No external effect is executed.

## Deployment plan

This adds a contract only. Runtime publication and N6 correlation follow in a
separate PR; this change does not claim completion of the actuation audit path.

## Related issues/PRs

N3 event vocabulary, N6 evidence joins and N7 governed actuation; follows #1934.

## Checklist

- [ ] Tests, standards and hooks pass
- [ ] Final-head review settled, all checks green, conflict-free merge
