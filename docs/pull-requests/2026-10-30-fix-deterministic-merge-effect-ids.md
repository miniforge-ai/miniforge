<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: repeatable merge and deploy effect ids; GitHub state decides merge retries

PR #2272.

## Overview

Merge and deploy effect ids become name-based UUIDs of (effect basis, attempt number) instead of `random-uuid`. Each
attempt takes the next free attempt id, so ids repeat across processes and two processes racing for one attempt collide
on its record. Whether an effect happened is decided by the external system's current state (GitHub for merges) or by
idempotent apply (deploys), not by replaying an earlier attempt's record.

## Motivation

Each merge and deploy attempt minted `random-uuid`, so a retry or a resumed run could not correlate with earlier
attempts, and two processes in the same run could both act. An earlier version of this PR replayed earlier records
instead; review showed that any record that was not `:failed` then blocked the effect for the rest of the run (a
`:proposed` record after a stop or crash, an OPEN PR ejected from a merge queue, a PR closed then reopened) and could
report a stale deploy success. This version drops replay.

## Changes in Detail

1. effect-transaction `attempt-id basis n` (a name-based UUID of `[basis n]`) and `free-attempt-id dir basis` (the first
  attempt with no record; `:exhausted` after 64).
2. Merge: `read-pr-head` returns the PR's head, state and merge commit. `attempt-merge` asks GitHub first: MERGED
  reports the merge, also when readiness no longer passes; CLOSED reports `:merge-closed-unmerged`; only an OPEN PR goes
  on to readiness and a merge under the next free attempt id of `[:effect/merge run-id repo pr-number head-sha]`. GitHub
  refuses a second merge of one PR, so a retry on an OPEN PR merges again rather than waiting on an old record.
3. Merge reconciliation accepts only a terminal PR state (`terminal-pr-states`: MERGED, CLOSED); an OPEN PR stays
  reconcilable instead of being recorded as a terminal mismatch.
4. Deploy: the basis is `[:effect/deploy execution-id kustomize-dir context namespace deployment-name manifest-sha256]`,
  opened after rendering. Each deploy takes the next free attempt id and applies; re-applying the same manifests is
  idempotent. Deploy reconciliation and outcome are unchanged from main.

## Testing Plan

1. `merge_effect_id_test`: a PR that merged after an earlier attempt is reported without `gh pr merge` running again; a
  CLOSED PR is reported and not merged; a MERGED PR is reported while readiness fails; two attempts on an OPEN PR take
  attempts 0 and 1. The first three fail on main (main merges again or reports not-ready); the last fails on main
  (random ids).
2. `deploy_effect_id_test`: two deploys in a run take attempts 0 and 1 of the basis and both apply; a changed manifest
  starts a new basis (fails on main: random ids).
3. `attempt_test`: ids are stable per basis and attempt; `free-attempt-id` returns attempt 0, then 1 once attempt 0 has
  a record in any state.
4. Ran locally under the shared JVM lock: pr-lifecycle merge, merge-authority, merge-governed, merge-grant-registry,
  merge-transaction, merge-effect-id, controller, integration; phase-deployment deploy-effect-id, deploy-governed,
  deploy-grant-registry, deploy-authority, deploy; effect-transaction attempt. 146 tests, 0 failures.

## Deployment Plan

No configuration change. Records written by earlier versions keep their random ids and are not matched.

## Related Issues/PRs

Follows #2258 and #2262. Deploy reconciliation still cannot tell an applied rollout from an old healthy one when a
process dies between the commit claim and the apply; closing that needs the apply to stamp the effect id on the
Deployment (a deploy-provider change, tracked separately).

## Checklist

- [x] Regression tests fail on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-deterministic-merge-effect-ids.md` (rule 721)
