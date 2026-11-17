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

  A section starts at a markdown heading: up to three leading spaces, one
  to six `#`, then a space, so a `#tag` line does not split a section. A
  field line may start with `-`. A section without all three, or with
  an unparseable date or outcome, decides nothing. Invalid miss ids in a
  list are dropped.
- `codex/harvest.clj` (new) and `codex.interface/harvest-decisions`: read
  the logs and return `{:decisions [...]}`, or a `:codex-unreadable`
  anomaly when the directory is missing, unreadable, or has a
  subdirectory that cannot be listed. `file-seq` would skip such a
  subdirectory silently, so the reader walks the tree itself, skipping
  symlinked directories so a link back to an ancestor cannot loop. The codex
  stays read-only.
- `codex-gap/report.clj`:
  - `harvest-decisions` reads the decisions through the codex interface,
    or returns nil;
  - `build-report` takes `:harvest`;
  - the learning slot is `:measured` with `:decided`, `:median-days`,
    `:open` and the open members, or `:harvest-unavailable` when there are
    no harvest records;
  - a miss named by several decisions pairs with the earliest one;
  - a `:review-queue` miss never counts as decided, so it stays out of
    every rate until its attribution is resolved;
  - `:decided` counts the misses a decision names, listed under
    `:decided-misses`; the median uses those with a usable `:miss/at`, and
    the rest are counted as undated;
  - latency is whole days between the miss's UTC date and the decision
    date.
- Message catalogs: the learning line in three forms (measured, none
  decided, unavailable), and two codex anomaly reasons.
- `bb.edn` `codex-gap-report`: passes the harvest decisions when
  `MINIFORGE_CODEX_PATH` is set.
- `work/`: the spec moves to `work/done/`. With #2519 merged, the codex-gap
  theme has no queued spec left: `QUEUE.md` loses its section (by hand,
  because `bb work:queue` could not run here), and `themes.edn` marks the
  theme `:complete` with a completion note.

## Testing Plan

- `codex/harvest_test.clj` (new):
  - complete sections decide;
  - incomplete sections and non-harvest files don't;
  - bad ids are dropped;
  - a missing directory is an anomaly;
  - a codex without logs has no decisions;
  - an indented heading splits sections and a `#tag` line does not, so two
    incomplete halves never merge into one decision;
  - a symlink back to the root is not followed.
- `codex_gap/interface_test.clj`, one test per acceptance criterion:
  - 3-day admission;
  - refusal counts;
  - earliest decision wins;
  - an unnamed miss stays open;
  - no harvest records reports unavailable;
  - decided misses are listed;
  - a `:review-queue` miss stays out of the rate;
  - a decided miss with a non-string timestamp counts as decided and
    undated, without crashing the report.
- Ran locally on a hand-built classpath: every codex and codex-gap test
  namespace, 81 tests after merging main with #2519, 0 failures. The unlistable-directory path has no test: this
  container runs as root, which can list a `chmod 000` directory, so the
  case cannot be reproduced here. `bb` is not installed here, so the bb
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
