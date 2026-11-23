<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: The codex gap report flags miss ledgers it could not fully read

## Overview

`bb codex-gap-report` now counts the runs whose miss ledger was not fully
read and marks the report header INCOMPLETE. Before this PR, a run whose
ledger failed to read was dropped without a trace, so the whole report
could look complete while runs were missing.

## Motivation

`read-ledger` has two lossy outcomes:

- an I/O or permission failure returns
  `{:codex-gap/anomaly :ledger-read-failed ...}` with no `:entries`. The
  task's `(mapcat :entries reads)` dropped that run, and nothing counted it;
- a torn line returns the readable entries plus a positive `:skipped`
  count. The header counted the skipped lines, but not the runs.

Every slot (coverage, routing, delivery, attention, learning) is computed
from the pooled ledger entries. A missing run therefore made every slot
partial, with nothing on the page to say so. #2519 fixed the same problem
for consultation files with `incomplete-read?` and
`:unreadable-consultations`; this PR applies it to miss ledgers.

## Changes in Detail

- `bb.edn` `codex-gap-report`: counts the run ledger reads that are
  `incomplete-read?` and passes the count as `:unreadable-ledgers`.
- `codex_gap/report.clj`:
  - `build-report` takes `:unreadable-ledgers` (default 0) and carries it
    at the top level of the report. It sits there, not in a slot, because
    it affects every slot;
  - `ledger-incomplete-suffix` renders the marker when the count is
    positive;
  - `render-report` appends that suffix to the header line. It treats a
    report map without `:unreadable-ledgers` as 0, so a map built before
    this change still renders (found in Copilot review).
- `messages/en-US.edn`: the header gains an `{incomplete}` placeholder,
  and `:report/ledger-incomplete-suffix` reads
  ", INCOMPLETE: miss ledger not fully read in {n} run(s)". "Not fully
  read" covers both a failed read and a lossy one.

## Testing Plan

- `codex_gap/interface_test.clj`,
  `incomplete-miss-ledgers-mark-the-report-incomplete`. Its helper
  `report-over-runs` follows the bb task's aggregation over real run
  directories:
  - a failed read (a directory where the ledger file belongs, which
    raises an IOException) is a `:ledger-read-failed` anomaly with no
    entries, and marks the header INCOMPLETE for 1 run;
  - a lossy read (a torn line) keeps its readable entries and marks the
    header INCOMPLETE for 1 run;
  - a failed and a lossy run together count as 2 runs;
  - a clean read shows no marker, and the option defaults to 0;
  - a report map without the count renders unmarked instead of throwing.
- With `report.clj` reverted to `main`, the new test fails 6 assertions.
- Ran locally on a hand-built classpath: every codex-gap and codex test
  namespace, 82 tests (267 assertions), 0 failures. clj-kondo on the changed files:
  0 errors, 0 warnings.

## Deployment Plan

No migration. Callers that do not pass `:unreadable-ledgers` get 0, and
their header is unchanged.

## Related Issues/PRs

- #2519: the same INCOMPLETE marker for consultation files.

## Checklist

- [x] Behavior and tests in the same PR (dewey 716)
- [x] New report text goes through the codex-gap message catalog
- [x] Existing report slots unchanged
