<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Register typed gate decision and rule evidence

## Overview

Formalize `gate/decision` and `gate/rule-applied` in N3 and admit their typed
payloads to the current journal. Product-owner approval was given on 2026-10-17.

## Motivation

Governance evidence must remain observable without accepting unregistered event
types or bypassing current-write validation. This does not change approval policy.

## Changes in Detail

- Foundations: N3 schemas, registry, emission obligations, and decision source.
- Typed current-write contracts and focused admission/rejection tests.
- Base branch: `main`; no open prerequisites.
- Producer migration follows this foundation before finalization PR #1952.

## Testing Plan

- Event-stream tests passed serially in Data Foundry, Miniforge, Core, and TUI.
- Built CLI: 39,160,326 bytes; isolated packaged tests passed (9 tests, 147 assertions).
- New disk integration is registered in the project runner and CI.
- Lint: zero errors/warnings; explicit stratification and Polylith checks passed.
- Pinned standards scan: zero violations across 4,419 files.
- Adversarial pass traced scope, derived decisions, nil fields, redaction, failed
  acknowledgment, restart, and retries. Schemas reuse decision-envelope and
  policy-clause interfaces; pure cross-field predicates remain separate.
- Whole PR remains below the 600-line reportable budget; no overrides.

This PR adds admission, not producer migration. Existing gate constructors and
gate orchestration still need profile-aware creation and failure propagation.

## Deployment Plan

Merge only after reviewed-head CI, including Build, passes and review findings are
resolved. Preserve branches and worktrees. No default journal switch in this PR.

## Related Issues/PRs

- #1952: OPSV evidence finalization consumes this prerequisite through migration.
- #2036: existing typed workflow admission pattern.

## Checklist

- [x] N3 amendment and registry agree
- [x] Typed admission and rejection coverage pass
- [x] Standards, lint, and consumer checks pass
- [ ] Reviewed-head CI and comments settle
