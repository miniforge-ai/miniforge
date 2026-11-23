<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): retain native experiment effect dispositions

## Overview

Retain correlated durable native-effect dispositions in the existing N3 event
family and N6 evidence assembly. Recording a disposition grants no authority.
API surface class 1: trusted in-process EDN, not an external execution endpoint.

## Motivation

N7 §5.5 requires temporary and recovery effects to preserve their actual grant,
decision and durable outcome. Native acknowledgements alone are not recovery.

## Changes in Detail

Read actual durable native records, validate their workflow and exact allowing
decision joins, and retain protected report material
before publishing acknowledged disposition events. Preserve publication failure
and uncertain outcomes. Share existing disposition factories and publication
behavior with PR effects without changing canonical native proposal payloads.

Admit and identify pending disposition drafts before retaining them for retry.
The injected missing-acknowledgement case exposed mismatched draft and published
identifiers. The shared draft path now preserves the canonical publication
identity, including for existing PR audit consumers.

## Testing Plan

Use actual native proposal, authority, transaction, artifact, event and evidence
producers. Cover mismatched joins, replay, pre-mutation audit refusal, uncertain
outcomes and failed publication. No live cluster calls or experiments.

- Strict lint: zero warnings/errors; every checked namespace has at most three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package each pass 149 lifecycle tests / 4,883 assertions,
  plus 39 audit/PR compatibility tests / 531 assertions.
- Curated OPSV/workflow/artifact integration: 324 tests / 7,220 assertions.
- Native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Fifty faults each trigger assertion failures with zero errors.
  Six new faults cover schema, correlation, protected retention, event
  acknowledgement, uncertain outcomes and pending-event identity. The remaining
  faults preserve native lifecycle safeguards.

## Deployment Plan

No execution activation. Runtime N4/N8 admission, exact independent grants,
pinned commits and every-exit recovery remain separate gates.

## Related Issues/PRs

Base: main after #2488. Runtime integration follows this evidence boundary.

## Checklist

- [x] Implement correlated native-effect audit and shared disposition plumbing.
- [x] Source, JVM, isolated package and fault-injection validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Normal signed commits, full current-head review, CI and owner merge.
