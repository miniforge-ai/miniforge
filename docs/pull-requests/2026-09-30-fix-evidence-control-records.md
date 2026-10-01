<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve canonical control records in evidence

## Overview

Align shared policy-check and control-action evidence with N6 sections 2.5 and 2.9.
Base branch: main. No unmerged prerequisites.

## Motivation

Canonical validation exposed a required implementation-only policy envelope and
a lossy control-action projection. Valid portable records must remain valid.

## Changes in Detail

- Make the optional policy envelope explicit in the shared schema.
- Preserve canonical action keys and structured results in the collector.
- Preserve supplied approval and state evidence without inventing missing facts.
- Test actual control-event producers and portable record validation.

Approval is conditional under N8. The schema accepts N6 `:status` and N8
`:approval-status` vocabulary. Missing states remain missing, not fabricated.
This corrects projection; capturing every control-executor snapshot remains a
separate N8 implementation obligation.

## Testing Plan

Run focused JVM regressions, affected Polylith consumers, rebuilt packaged CLI
tests, namespace strata checks, and a root incremental standards scan.

Final focused JVM and packaged tests pass: seven tests, 35 assertions.
All three evidence-bundle consumer suites pass. Kondo and inferred strata pass.
The packaged CLI was rebuilt with the production changes.
After integrating current main, focused JVM and rebuilt packaged suites pass
16 tests and 87 assertions, including optional producer projections.

## Deployment Plan

Merge after clean current-head review and all CI checks. No external deployment.
Integrate this prerequisite into canonical validation before merging that PR.

## Related Issues/PRs

Prerequisite for #1959, addressing canonical review findings 4152222971 and 4152223005.

## Checklist

- [x] Verify schemas and live collector projection against N6/N8.
- [x] Complete JVM and packaged tests and adversarial code review.
- [x] Root incremental standards scan: 4,108 indexed files, zero changed-file findings.
- [ ] Settle current-head review and CI.
