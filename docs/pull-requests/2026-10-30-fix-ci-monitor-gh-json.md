<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Read real gh pr checks output and fetch failed-job logs

## Overview

The PR lifecycle CI monitor now reads the output gh 2.102 actually produces and, when CI fails,
fetches the failed jobs' logs for the fixer.

## Motivation

`get-pr-checks` asked for `name,state,conclusion,detailsUrl`. gh 2.102 rejects `conclusion`
(`Unknown JSON field`, exit 1), so every poll failed and the monitor reported `:unknown` until its
1h timeout. The success path wrapped gh's JSON array in a second `[...]` and read it as EDN.
`compute-ci-status` grouped on `state` + `conclusion`, so real checks fell to `:unknown` and a
running check next to a passing one counted as `:success`. `get-check-logs` was a stub, and
`gh run view --log-failed` was never called, so the fixer got `:ci/logs` = nil.

## Changes in Detail

1. Request `name,state,bucket,link,workflow`, parse with cheshire, classify on `bucket`.
   Output that is not a JSON array is an error, not "no checks".
2. `no checks reported` (right after a push) polls as pending. (With `--json`, gh exits 0 while
   checks are pending.)
3. On failure, fetch `gh run view <run-id> --log-failed` once per GitHub Actions run (run id from
   the check's `link`, at most 3 runs), strip ANSI codes (ESC or gh's literal `^[` on a pipe) and
   timestamps, and return them as `:ci/logs` (`:ci/log-errors` on fetch errors). A log over 12000
   characters keeps its failure lines (FAIL/ERROR, `##[error]`, `error:`, exceptions, up to half)
   and then its tail: this repo's Test job can put the failing assertion ~200k characters before
   the end, and gh prints several failed jobs one after another.
4. gh serves a run's failed-job log only once the whole run has finished, and a fetch can fail
   transiently. A failure with no log yet polls as `:pending` while other checks are pending or a
   fetch failed, bounded by the monitor timeout.
5. A cancelled check no longer counts as a pass. A cancelled-only run is terminal, so the controller
   stops with `:unknown-ci-status` instead of polling for an hour.
6. New prose goes through the system message catalog.

## Testing Plan

`ci_checks_gh_test.clj` replays gh 2.102.0 output captured on 2026-10-29 from this repo's PRs
(failing #2244, pending #2251, passing #2249, gh's error texts, and `gh run view --log-failed`),
stored in `gh-fixtures/gh_2_102.edn`. Its fake gh rejects `--json` fields the way gh does. The
replay tests fail against the previous code. Existing tests use the gh check shape instead of
invented `{:state "COMPLETED" :conclusion ...}` maps; two that asserted the old misclassification
were corrected. A test covers a failure while sibling checks still run.

## Deployment Plan

No configuration or data migration. Requires gh 2.x with `pr checks --json` bucket support.

## Known gaps

A poll right after a fix push can read the previous commit's finished checks (gh reads the PR's
latest commit, which GitHub may not have moved yet); checking each run's head commit against the
pushed one comes in a follow-up. Jobs gated by `needs:` are not listed until they start, so a gap
between jobs can look like "nothing pending". `:cancelled` and `:neutral` end the lifecycle as
`:unknown-ci-status` in `controller.clj`, which is over the layer budget and needs a split first.

## Related Issues/PRs

- #2254 (merged): split of `ci_monitor.clj` into `ci-checks` and `ci-monitor`, needed first.
- #2259: the fixer uses these logs.

## Checklist

- [x] Regression tests fail on main
- [x] Stratum lint, clj-kondo, commit and PR budgets pass
- [x] Copilot review comments answered
