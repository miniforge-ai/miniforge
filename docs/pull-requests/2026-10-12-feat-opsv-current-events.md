<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: admit typed OPSV drafts to durable event streams

## Overview

Admit the ten N3 §3.14 OPSV event types through the current durable publication boundary.

## Motivation

N7 evidence checkpoint and finalization work needs acknowledged durable OPSV events.
The current publisher previously admitted only chain and selected supervisory profiles.

## Changes in Detail

Derive draft admission from the existing authoritative OPSV schemas, removing only the publisher-owned sequence requirement.
Preserve payload requirements and cross-field disposition predicates. Require version 1.0.0 and explicit workflow scope.
The shared envelope boundary still refuses caller-supplied sequence numbers and owns privacy validation.

This adds typed admission, not automatic producer migration.
Existing legacy constructors and historical records remain unchanged.
Workflow/gate event support and OPSV host stream selection remain separate integration work.

## Testing Plan

Verify all ten event families, malformed payload refusal, disposition consistency, scope isolation, retry identity, and
durable journal reopening.

- Focused source-overlay checks: 9 tests / 188 assertions passed.
- All four event-stream consumer suites passed serially in 1m24s, including real journal reopening under the JVM.
- Rebuilt CLI: 39,148,125 bytes. Isolated package checks (no production overlays): 41 tests / 506 assertions passed.
- Staged Clojure lint and stratum checks passed without warnings.
- Explicit pinned Miniforge standards scan: zero violations across 4,388 files.

The initial disk fixture used a noncanonical temp path and a stale query option; both were corrected to the existing
boundary contracts.
No storage or query validation was weakened.

## Standards Review

Adversarial review traced fresh drafts through admission, redaction, commit receipts, scoped queries, and recovery.
Schema projection changes only top-level sequence requirements; nested maps and disposition predicates remain authoritative.
Shared constructor fixtures avoid duplicated payload maps; legacy constructor behavior remains covered.
Unsupported families still have no generic envelope fallback. No runtime defaults or historical records are rewritten.
Review correction: both public API entry points now document typed OPSV draft support.

## Deployment Plan

No default stream switch or external provider effect. Callers must deliberately construct a sequence-free current draft.
This prerequisite alone does not complete N7 runtime evidence or execution recovery.

## Related Issues/PRs

Base: clean main at #2032 (`2679ea4ca`). Supports unfinished #1956, #1952, and #1948.
Independent of #2033's required-binding execution policy.

## Checklist

- [x] Typed admission and durable regression coverage
- [x] Serial consumers, package verification, and standards review
- [ ] Signed hooks, exact-head review, and all CI before merge
