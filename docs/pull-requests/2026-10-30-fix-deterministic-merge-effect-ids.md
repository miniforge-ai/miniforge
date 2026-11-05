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
attempts, and a retry of a PR that had already merged asked readiness again and stalled on `:not-ready`. An earlier
version of this PR replayed earlier records instead; review showed that any record that was not `:failed` then blocked
the effect for the rest of the run (a `:proposed` record after a stop or crash, an OPEN PR ejected from a merge queue, a
PR closed then reopened) and could report a stale deploy success. This version drops replay.

## Changes in Detail

1. effect-transaction `attempt-id basis n` (a name-based UUID of `[basis n]`) and `free-attempt-id dir basis` (the first
  attempt with no record; `:exhausted` after 64).
2. Merge: `read-pr-head` returns the PR's head, state and merge commit from one `gh pr view`. `attempt-merge` asks
  GitHub first: MERGED reports the merge, also when readiness no longer passes; CLOSED reports `:merge-closed-unmerged`;
  only an OPEN PR goes on to readiness and a merge under the next free attempt id of `[:effect/merge run-id repo
  pr-number head-sha]`.
3. Deploy: the basis is `[:effect/deploy execution-id kustomize-dir context namespace deployment-name manifest-sha256]`,
  opened after rendering. Each deploy takes the next free attempt id and applies; re-applying the same manifests is
  idempotent. Deploy reconciliation and outcome are unchanged from main.

## Known limits

1. Two processes collide on a record only when both read the same free attempt before either records it. A process that
  reads after another's proposal takes the next attempt. For merges, GitHub's refusal of a second merge of one PR (bound
  with `--match-head-commit`) stops a double merge; the refused attempt records `:failed`, and its next retry sees MERGED.
2. Each process publishes `:pr/merged` on its own in-process event bus the first time it sees the merge, so a second
  process of one run (a resume, or the race above) publishes its own event on its own bus. Nothing forwards PR lifecycle
  events to a shared stream today.
3. The controller treats `:merge-closed-unmerged` as blocked and retries it, as it does `:not-ready` on main.
4. `merge-transaction/reconcile!` has no runtime caller and is unchanged. It records an OPEN PR as a terminal mismatch,
  so a caller that wires it must accept only MERGED or CLOSED answers.

## Testing Plan

1. `merge_effect_id_test` (its `gh` stub answers both the JSON read and a `--jq .headRefOid` read): a PR that merged
  after an earlier attempt is reported without `gh pr merge` running again; a CLOSED PR is reported and not merged; a
  MERGED PR is reported while readiness fails; two attempts on an OPEN PR take attempts 0 and 1. Run against main's
  pr-lifecycle (with this PR's attempt ids added so the namespace loads), the first two fail because `gh pr merge` runs
  again, the third because main reports `:not-ready`, and the last because main's ids are random.
2. `deploy_effect_id_test`: two deploys in a run take attempts 0 and 1 of the basis and both apply; a changed manifest
  starts a new basis (fails on main: random ids).
3. `attempt_test`: ids are stable per basis and attempt; `free-attempt-id` returns attempt 0, then 1 once attempt 0 has
  a record in any state.
4. Ran locally under the shared JVM lock: every pr-lifecycle `merge*`, `controller*` and `home_paths` test namespace,
  effect-transaction `attempt_test`, phase-deployment `deploy_effect_id_test`. 106 tests, 0 failures. Deploy sources are
  unchanged since the previous run of the deploy-governed, deploy-grant-registry, deploy-authority and deploy suites.

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
