<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Emit gate evidence using the journal profile

## Overview

Migrate the registered gate decision and rule constructors to the current
journal's sequence-free input contract while retaining legacy stream behavior.

## Motivation

N3 admission alone cannot accept a constructor that reserves its own position.
This adapter layer connects existing gate evidence producers to the new contract.

## Changes in Detail

- Base: `main`; prerequisite #2040 is merged.
- Share workflow envelope profile selection with the existing OPSV constructors.
- Keep gate payload construction separate from the event-stream core.
- Localize constructor messages and test current and legacy profiles.

## Testing Plan

- All four event-stream consumer projects passed serially (1m40s).
- Built CLI: 39,160,930 bytes; isolated package tests passed (16 tests, 286 assertions).
- The packaged disk test now uses the public constructors, not hand-built drafts.
- Kondo: zero warnings/errors; explicit stratification passed.
- Pinned standards scan: zero violations across 4,425 files.
- Adversarial pass covered runtime decisions, all five rule statuses, legacy
  payload compatibility, forged options, failed identity generation, and retries.
- Profile selection has one definition; the interface delegates to gate-events,
  which composes workflow-envelope and the lower event core without a cycle.
- Whole follow-up: 195 reportable lines; no overrides.

Invalid optional values remain present for admission to reject. Only absent/nil
values are omitted; false must not silently erase invalid policy metadata.

## Deployment Plan

Review every pushed head and wait for all CI including Build before merge.
No default stream switch in this PR.

## Related Issues/PRs

- #2040: approved N3 gate contracts and typed admission.
- #1952: OPSV finalization needs a current-compatible decision constructor.
- Gate orchestration failure propagation remains a separate follow-up.

## Checklist

- [x] Constructors preserve evidence and journal ownership of positions
- [x] Both profiles and anomaly propagation verified
- [x] Standards, lint, build, and serial consumers pass
- [ ] Prerequisite merged; final-head review and CI pass
