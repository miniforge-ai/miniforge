<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: admit canonical workflow lifecycle drafts

## Overview

Foundation slice for durable N7 workflow evidence: typed current lifecycle admission under N3 §§3.1 and 3.21.

## Motivation

Legacy completion records permit contradictory status values and legacy failure records omit required classification.
Current publication needs explicit canonical contracts without rewriting historical schemas or records.

## Changes in Detail

Add six sequence-free profiles: started, phase-started, phase-completed, completed, failed, and cancelled.
Shared identity and terminal constraints compose with typed phase acts and cancellation-source requirements.
Blocked phases require refusal reasons; redirected phases require transition requests. Failure classes use N1's taxonomy.
Control-action cancellations require an action reference. Terminal events reject competing status discriminators.

Protect workflow specification, intent, artifact/evidence references, phase acts, classification, and cancellation provenance.
Ordinary context and error observations remain redactable. Public API documentation lists the supported family.
No generic event fallback, gate registry amendment, producer migration, or default host-stream switch.

## Testing Plan

- Four event-stream consumer projects passed serially (1m25s).
- New contract and real-journal tests: 8 tests / 133 assertions per consumer.
- Rebuilt CLI: 39,150,685 bytes; isolated package: 30 tests / 475 assertions, no production source overlays.
- Clojure/stratum lint and workspace checks passed; pinned standards scan reported zero violations.

## Standards Review

Schemas compose existing refusal, transition, and failure vocabularies without copying them.
Shared payload fixtures drive required-field and durable-publication tests. Named operations keep journal setup separate.
Trace confirms common admission still rejects caller positions before committing; retries retain the original receipt,
identity conflicts do not append, and closed streams refuse publication. Journal reopening preserves scoped records.
These are event admission contracts, not stateful lifecycle authority or a claim that the host migration is complete.

## Deployment Plan

Admission only. Producer migration follows this foundation; existing legacy callers retain their behavior.

## Related Issues/PRs

Base: verified clean main at `4f268b147` (#2034). Independent of pending OPSV producer PR #2035.
Supports the N7 evidence stack (#1956, #1952, #1948).

## Checklist

- [x] Canonical contract and adversarial standards review
- [x] Serial consumer, package, and standards validation
- [ ] Signed hooks, exact-head review, and all CI before merge
