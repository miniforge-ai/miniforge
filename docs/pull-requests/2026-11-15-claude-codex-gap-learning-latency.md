<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: The codex gap report measures learning latency

## Overview

The learning slot of `bb codex-gap-report` now pairs each recorded miss
with the earliest harvest decision that names it. It reports:

- the median days from miss to decision;
- how many misses are decided;
- the open candidates no decision names.

Before this PR the slot printed "learning: not instrumented".

## Motivation

Thesium Codex T2 §4.1 item 6 defines the learning slot as the median time
from a miss to a codex node admitted for it, or to an explicit refusal.
T1 §8.4 makes those decisions dated harvest records. Harvest entries
cited their sources in prose, so nothing could pair a miss with the
decision that closed it.

## Changes in Detail

- New harvest entry fields. A section of a `HARVEST-*.md` log anywhere
  under the codex directory decides misses when it carries all three:
  - `decided::` (an ISO date);
  - `outcome::` (`admitted` or `refused`);
  - `misses::` (comma-separated `:miss/id` UUIDs).

  A field line may start with `- `. A section without all three, or with
  an unparseable date or outcome, decides nothing. Invalid miss ids in a
  list are dropped.
- `codex/harvest.clj` (new) and `codex.interface/harvest-decisions`: read
  the logs and return `{:decisions [...]}`, or a `:codex-unreadable`
  anomaly when the directory is missing or unreadable. The codex stays
  read-only.
- `codex-gap/report.clj`:
  - `harvest-decisions` reads the decisions through the codex interface,
    or returns nil;
  - `build-report` takes `:harvest`;
  - the learning slot is `:measured` with `:decided`, `:median-days`,
    `:open` and the open members, or `:harvest-unavailable` when there are
    no harvest records;
  - a miss named by several decisions pairs with the earliest one;
  - latency is whole days between the miss's UTC date and the decision
    date.
- Message catalogs: the learning line in three forms (measured, none
  decided, unavailable), and two codex anomaly reasons.
- `bb.edn` `codex-gap-report`: passes the harvest decisions when
  `MINIFORGE_CODEX_PATH` is set.
- `work/`: the spec moves to `work/done/`. Its `QUEUE.md` row is removed by
  hand, because `bb work:queue` could not run here.

## Testing Plan

- `codex/harvest_test.clj` (new):
  - complete sections decide;
  - incomplete sections and non-harvest files don't;
  - bad ids are dropped;
  - a missing directory is an anomaly;
  - a codex without logs has no decisions.
- `codex_gap/interface_test.clj`, one test per acceptance criterion:
  - 3-day admission;
  - refusal counts;
  - earliest decision wins;
  - an unnamed miss stays open;
  - no harvest records reports unavailable.
- Ran locally on a hand-built classpath: every codex and codex-gap test
  namespace, 69 tests, 0 failures. `bb` is not installed here, so the bb
  task and lint run in CI only.

## Deployment Plan

No migration in miniforge. Until harvest entries carry the three fields,
the slot reports "no miss decided by a harvest entry yet". The vault-side
convention is a change to the codex's harvest log format.

## Related Issues/PRs

- `work/done/codex-gap-learning-latency.spec.edn`: the spec.
- #2519: the consultation match rate, the other unmeasured slot.

## Checklist

- [x] Behavior and tests in the same PR (dewey 716)
- [x] Codex files read only through `ai.miniforge.codex.interface`
- [x] New report text goes through the message catalogs
