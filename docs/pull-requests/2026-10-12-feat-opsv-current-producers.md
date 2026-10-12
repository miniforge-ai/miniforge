<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: construct OPSV events for current publication

## Overview

Connect existing OPSV constructors to sequence-free current publication while preserving legacy callers.

## Motivation

Typed admission alone cannot publish legacy constructor output, which already carries a sequence number.
Current producers must leave position allocation to durable publication and propagate identity failures intact.

## Changes in Detail

Select the envelope profile in a small stratified helper: current streams produce workflow-scoped drafts;
legacy streams retain their existing positioned envelopes. Both canonical and legacy identity failures return unchanged.
Localize canonical OPSV messages without changing their rendered text. Reuse the ten shared constructor fixtures.

Phase delivery now admits current drafts before retry identification and accumulates acknowledged records.
Its admission remains OPSV-specific and converts legacy-format preparation failures to canonical phase failures.
Current receipts require a position and matching identity. Evidence references alone cannot acknowledge current publication.
Retries submit fresh drafts directly to indexed publication, which retains the committed occurrence timestamp
and applies content and availability checks without querying the event history.
Legacy assembly-backed retry behavior is preserved. Workflow/gate producers and default host selection remain separate work.

## Testing Plan

All checks ran serially after integration with merged #2034.

- Four consumer projects passed event-stream and phase-opsv tests where consumed (1m55s).
- After the indexed-retry correction, both phase consumers passed again (40s).
- Current audit regressions: 7 tests / 49 assertions in each phase consumer.
- Rebuilt CLI: 39,151,390 bytes. Isolated package: 48 tests / 639 assertions, no production source overlays.
- Clojure lint, stratum lint and workspace checks passed; pinned standards scan reported zero violations.

## Standards Review

Separate profile selection from draft decoration and keep the existing payload selection authoritative.
Review found that the canonical-only anomaly macro would decorate legacy failures; both boundaries now use the dual predicate.
Tests verify exact canonical messages, both failure formats, identity, payloads, retry receipts, and publication-time redaction.
The retry audit also found a closed-stream bypass when returning a stored receipt directly; current retries now call publication.
Copilot identified a redundant full-log scan; current retries now rely on the publisher's indexed deduplication.
Cross-family current events are refused by the OPSV adapter. Both identity error formats prevent provider mutation.
Post-mutation publication failures retain the durable outcome for audit-only retries without repeated provider calls.

## Deployment Plan

No default stream switch or provider mutation. Full workflow/gate integration remains a separate prerequisite.

## Related Issues/PRs

Base: clean main at #2034 (`4f268b147`), integrated once after reviewed merge.
Supports the existing N7 evidence stack (#1956, #1952, #1948).

## Checklist

- [x] Current and legacy constructor coverage
- [x] Standards, serial validation, and package checks
- [ ] Signed hooks, exact-head review, and all CI before merge
