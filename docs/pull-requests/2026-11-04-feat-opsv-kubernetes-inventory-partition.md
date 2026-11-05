<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Partition captured Kubernetes inventory by proven controller membership

## Overview

Derive the selected Deployment's member view from a complete namespace inventory.
Retain the entire original inventory unchanged alongside that semantic view.

## Motivation

Replica admission must distinguish members by controller identity, not labels.
Outside observations must remain available for independent drift comparison.

## Changes in Detail

Validate inventory shape at ingress. Require complete lists, unique physical and
logical identities, the declared root identities and consistent owner links.
Reject ambiguous controllers, contradictory known parents, unresolved ReplicaSet
parents and direct selected-Deployment Pod ownership.

Restore omitted list-item TypeMeta from admitted list context in the semantic
view only. Reuse the captured-membership model to prove the selected chain.
Preserve unmanaged and outside observations in the original inventory.

Ownership: `phase-opsv` read-side domain. This performs no native reads or effects.
It proves neither freshness, template/configuration equivalence, acceptable replica
counts, SLO compliance nor execution authority. Outside data is not ignored or
declared safe merely because it is outside the selected chain.

## Testing Plan

Use captured native fixtures. Cover mixed chains, omitted types, malformed and
incomplete lists, duplicate identities, changed roots, ambiguous and missing
parents, matching labels, Pending Pods and zero population.
Run product, isolated package, mutation, standards, signed-hook, CI and full-head
review gates.

## Deployment Plan

Pure explicit API only; no default execution or cluster changes. Publish after
the complete-inventory prerequisite merges and its inherited diff is excluded.

## Related Issues/PRs

N7 §6.3; captured membership #2359; namespace inventory #2361.

## Checklist

- [ ] Prove selected membership without dropping outside raw observations.
- [ ] Keep identity, configuration, freshness, health and authority distinct.
- [ ] Complete all local, owner, CI and full-review gates.
