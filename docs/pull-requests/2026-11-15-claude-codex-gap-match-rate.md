<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: The codex gap report measures the consultation match rate

## Overview

The coverage slot of `bb codex-gap-report` now reports how many attempted
consultations matched a situation, out of how many were attempted, and
lists the situations that matched nothing. Before this PR the slot printed
"consultation-match-rate: not instrumented".

## Motivation

Thesium Codex T2 §4.1 item 1 defines coverage as matched consultations over
consultation attempts. The consultation records already existed per run, but
a `:skipped` record did not say why it was skipped. An unreadable codex and
a situation with no board looked the same, so no honest rate was possible.

## Changes in Detail

- `codex_gap/ledger.clj`: `build-consultation-entry` records the summary's
  anomaly as `:consultation/anomaly`, nil when there is none.
- `codex_gap/report.clj`:
  - `attempt-anomalies` names the two skip causes that count as attempts:
    `:situation-unmatched` and `:situation-ambiguous`;
  - `consultation-match` classifies each entry. `:pinned` is a match. A
    `:skipped` entry with an attempt anomaly is an unmatched attempt. A
    `:skipped` entry with no recorded anomaly predates this field and is
    counted as unclassified. Everything else (unmapped, unconfigured, an
    unreadable codex) is not an attempt;
  - `build-report` takes `:consultations` and puts the result under
    `[:coverage :consultation-match]`, replacing the
    `:consultation-match-rate :not-instrumented` placeholder. `:rate` is
    nil when nothing was attempted;
  - `render-report` prints the rate with its counts, or "no consultation
    attempted", and lists the unmatched situations.
- `messages/en-US.edn`: the coverage line and three new message keys.
- `bb.edn` `codex-gap-report`: reads each run directory's consultations
  next to its ledger and passes them in. Their unreadable lines are added
  to the report's skipped-line count. A run whose consultation file cannot
  be read at all is counted, and the rate is marked INCOMPLETE rather than
  reported over the remaining runs as if whole.
- `work/`: the spec moves to `work/done/`. `QUEUE.md` loses its row, by
  hand, because `bb work:queue` could not run here; the result matches
  what the generator would produce.

## Testing Plan

- `codex_gap/interface_test.clj`, fixtures built through
  `build-consultation-entry`:
  - an entry from an unmatched summary carries the anomaly;
  - 3 pinned, 1 unmatched, 1 unreadable codex and 2 unmapped report
    3 of 4 (75%), with the unmatched situation as the member;
  - a `:skipped` entry with no anomaly is unclassified, not an attempt;
  - no attempts reports "no consultation attempted";
  - unreadable consultation files mark the rate INCOMPLETE, even when
    nothing was attempted;
  - consultations written with `record-consultation!` and read back with
    `read-consultations` produce the rate, which is the path the bb task
    takes.
- Ran locally on a hand-built classpath: every codex-gap and codex test
  namespace, 67 tests, 0 failures. `bb` is not installed here, so the bb
  task itself and lint run in CI only.

## Deployment Plan

No migration. Consultation entries written before this change have no
`:consultation/anomaly`; their skipped entries are reported as
unclassified rather than guessed.

## Related Issues/PRs

- `work/done/codex-gap-consultation-match-rate.spec.edn`: the spec.
- #2406: the codex atlas that documents the gap report.

## Checklist

- [x] Behavior and tests in the same PR (dewey 716)
- [x] Existing report slots unchanged
- [x] New report text goes through the codex-gap message catalog
