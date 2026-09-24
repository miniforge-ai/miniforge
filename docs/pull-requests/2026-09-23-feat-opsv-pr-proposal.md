<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: prepare evidence-bearing OPSV pull requests

## Overview

Add the pure proposal boundary consumed by governed OPSV PR execution. Every
proposal carries evidence and rollback instructions, and failed verification
forces a draft PR with explicit merge-ineligibility text.

## Motivation

N7 section 7.2 requires evidence and rollback instructions in every PR body.
Section 6.2 allows failed candidates for review only when they cannot merge.
Construct and hash the exact payload before requesting scoped authority.

## Changes in Detail

- Add the `opsv-actuation` component with a validated class-1 (internal EDN) API.
- Generate localized PR content from verification and evidence.
- Hash the complete provider payload for the PR-creation grant scope.
- Keep proposal construction pure and separate from provider mutation.
- Package the public boundary explicitly in the Miniforge project, as with the
  existing Ariadne foundation boundaries, for its next application-layer consumer.

## Testing Plan

Validate required inputs, success and failed-verification payloads, stable hashes
and digest changes when provider content changes. Run component tests, Polylith,
lint and an adversarial standards review.

Results: 8 component tests / 73 assertions pass in the Miniforge project.
Pre-commit passes kondo with zero warnings/errors, stratification, formatting,
347 smoke tests / 1,310 assertions and 8 compatibility tests / 671 assertions.
The adversarial pass covers failed, contradictory and empty verification, plus
missing evidence or rollback instructions. It also checks stable serialization,
changed provider fields and literal placeholder text in operator content.
The review pass also rejects incomplete Git object IDs and non-portable
verification values before serialization. Evidence accepts scalar EDN, vectors
and string/keyword-keyed maps; functions, runtime objects and custom tags fail
at the public boundary. Both 40- and 64-character lowercase object IDs pass.
The numeric test includes an ordinary EDN Double, BigDecimal and ratio.

The finalized body includes the preallocated transaction and workflow IDs.
Changing either changes the authorized payload hash. The transaction joins to
the grant and allowing envelope in N6 evidence, as N7 section 5.4 requires.
Grant IDs are issued after hashing; embedding the grant ID in its own authorized
payload would create a circular dependency. The executor must persist all three
references before emission and must not append anything to the authorized body.

Stratification follows the per-file reference graph in the Clojure standard.
The public wrapper has no same-file dependencies; cross-namespace stratum
numbers are not global abstraction ranks. The stratification lint passes.

## Deployment Plan

No external actions or migration. The follow-up application layer uses these
proposals with ExecutionGrants and EffectTransactions.

## Related Issues/PRs

`work/n07-opsv-governed-actuation.spec.edn`; PR #1910 adds the matching grant scope.

## Checklist

- [x] Tests and standards review pass.
- [ ] CI passes and review settles.
