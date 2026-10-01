<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve canonical control records in evidence

## Overview

Align shared policy-check and control-action evidence with N6 sections 2.5 and 2.9.
Base branch: main. Authorization prerequisite #1970 is merged.

## Motivation

Canonical validation exposed a required implementation-only policy envelope and
a lossy control-action projection. Valid portable records must remain valid.

## Changes in Detail

- Make the optional policy envelope explicit in the shared schema.
- Preserve canonical action keys and structured results in the collector.
- Preserve supplied approval and state evidence without inventing missing facts.
- Test actual control-event producers and portable record validation.
- Register a server-owned dashboard listener per instance and retain its ID in
  control evidence; ignore body-supplied identities and release it on shutdown.
- Acquire that identity only at successful startup; drain HTTP before releasing it,
  even when cleanup fails. Preserve supplied request metadata at event publication.
- Gate all HTTP requests with 503 during startup and publish discovery only after
  identity attachment. Validate supplied N8 capability and approval vocabularies.
- Roll back acquired HTTP, watcher and listener resources when startup fails;
  retain the original exception and suppress any secondary cleanup failure.
  This compatibility behavior lives at the HTTP startup boundary, not in domain logic.

Approval is conditional under N8. The schema accepts N6 `:status` and N8
`:approval-status` vocabulary; when both are supplied they must agree. Missing
states remain missing, not fabricated.
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
Dashboard producer regressions pass eight tests and 48 assertions, covering
stable registered identity, body spoofing, assembled evidence, and listener cleanup.
The full dashboard consumer suite passes. Rebuilt packaged dashboard and control
record regressions pass 15 tests and 83 assertions.
Final lifecycle/metadata regressions pass 24 tests and 123 assertions in the rebuilt
CLI. All four event-stream consumer suites and the dashboard suite pass serially.
Approval-alias regressions pass on the JVM and rebuilt packaged CLI: two tests,
21 assertions, including agreement, contradiction, malformed and absent statuses.
After the N8 vocabulary and HTTP readiness fixes, all three evidence consumers
and the dashboard suite pass serially. Rebuilt packaged readiness/schema/evidence
regressions pass eight tests and 60 assertions. Inferred strata and Kondo pass.
Post-bind rollback and cleanup-error preservation regressions pass with the full
dashboard consumer suite. Rebuilt packaged schema, lifecycle, identity, authorization
and event-metadata regressions pass 13 tests and 104 assertions.

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
