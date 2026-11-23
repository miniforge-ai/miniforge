<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: retain events using the captured physical checkpoint location

## Overview

Workflow integration for the product-owner-approved N3 paired-location amendment.
Base: main. Contract foundation #2299 is merged and integrated, including its
payload-version admission correction.

## Motivation

Generation files and trusted run-directory aliases make parent-count inference
and root-plus-UUID reconstruction insufficient to identify actual storage.

## Changes in Detail

- Capture the physical run directory in the store's successful write receipt.
- Announce paired root/run metadata at payload 1.1.0 without changing logical UUIDs.
- Decode current and legacy announcements at the event-file boundary; keep events
  on uncertainty and use physical run directories for retention.
- Separate successful-write event projection from reporting orchestration rather
  than deepen runner layering or duplicate phase/snapshot field assembly.

## Testing Plan

Regression tests for real producer receipts, aliased run directories, generations,
legacy forms, unknown records, and release after checkpoint deletion. Run focused
tests and all affected workflow consumers serially under the shared JVM lock.
The old consumer reproduced four failed assertions across the two aliased-layout
cases. Updated focused tests pass: 22 tests, 83 assertions, zero failures/errors.
Unknown, malformed, relative, wrong-run and unsupported-version locators preserve
retention and return an unavailable root lookup instead of guessing.

The first full workflow run exposed eight owner-lock errors in phase-event tests.
Their helper stubbed checkpoint writes but still acquired the real owner lock
under the operator's live default root. The sandbox refused those writes. The
helper now runs under the existing temporary-root fixture, passing its root
explicitly, so real ownership enforcement remains active without live-state writes.

All three workflow-consuming product suites then passed (3m32s). The final run, against
the corrected #2299 schema and additional legacy-relative/misversioned locator
coverage, also passed (4m03s). Lint has zero warnings/errors; actual strata remain
within three. Aliased-layout filesystem cases require POSIX; pure decoder tests
use platform-native absolute paths.

Fresh CLI build succeeded: 6,880,604-byte stripped jar. Isolated packaged
Babashka acceptance passed 24 tests and 101 assertions from `/private/tmp`, using
only that jar and workflow test fixtures (no production-source overlays).

After merging actual main, including concurrent runtime/release changes, all
three workflow-consuming product suites passed again (3m41s). A fresh CLI rebuild and
the same isolated packaged acceptance also passed: 24 tests, 101 assertions.

The adversarial pass traced captured aliases through producer, journal and
retention boundaries. It also checked first-write snapshots, deployed root-only
records, future minor profiles, unknown locators and newer unreadable events.
The initial tracked-head standards scan had zero violations; its reportable
integration size was 431 lines before review corrections.

Copilot found mixed legacy/Snowflake filename ordering could select an older
announcement. The regression reproduced ten failures across five tests. The
reader now orders both grammars with the shared timestamp parser. Unknown or
malformed times, unreadable newer records and conflicting same-time locators
remain uncertain. Agreeing same-time locators remain usable.

The first full ordering run exposed an old fixture whose successive IDs all
encoded the same millisecond. Its factory now encodes the intended distinct
timestamps. Focused ordering and retention acceptance then passed 20 tests and
68 assertions. Data Foundry was explicitly selected but does not include the
workflow brick; Polylith correctly excludes it from these component test runs.
The normal hook also required a real namespace split after inferring a fourth
stratum. File discovery and chronological grouping now form a separate I/O
boundary; announcement interpretation remains separate, within three strata.

After the split and current-main merge, all three workflow-consuming product
suites passed again (2m49s). A fresh CLI build and isolated packaged acceptance
passed 29 tests with 114 assertions, including the mixed-format ordering cases.
Normal hooks and signing passed; the final reportable PR size is 528 lines.

Fresh review found two additional boundary gaps: SMART calendar parsing could
normalize impossible dates, and parsed JSON without a valid type was skipped.
New regressions reproduced 13 failures. Strict calendar validation and valid
Snowflake identity checks now precede ordering. Non-map records and missing or
unqualified event types remain uncertain, while identifiable non-writes can
still be skipped. Focused acceptance passes 23 tests and 94 assertions.
All three workflow-consuming product suites then passed again. A fresh CLI
build and isolated packaged acceptance passed 30 tests and 128 assertions.
The corrected complete PR remains within the normal budget at 550 lines.

## Deployment Plan

No journal migration; retain both legacy forms. No change to filesystem trust,
permission policy, generation publication or locking introduced by prior PRs.

## Related Issues/PRs

Follows #2094, #2280, #2287 and #2288. This integration is not an N7 completion claim.

## Checklist

- [x] Implement and verify regression tests
- [x] Standards/adversarial review, normal hooks and size gates
- [ ] Fresh Copilot review and all CI gates
