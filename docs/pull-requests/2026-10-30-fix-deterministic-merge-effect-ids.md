<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: fix merge and deploy effect ids per run and target; settle earlier attempts

PR #2272.

## Overview

Merge and deploy effect ids are now fixed per (run, exact target, attempt) instead of random, so a second attempt at the
same effect in the same run settles the first one instead of running it again. The id helper lives in effect-transaction
for the other effect sites to reuse.

## Motivation

Each merge and deploy attempt minted `random-uuid` for its effect id. A monitor retry or a resumed run had no way to
find the record an earlier attempt left, so it issued new authority and ran `gh pr merge` or the cluster apply again.

## Changes in Detail

1. effect-transaction `attempt-id basis n`: a name-based UUID of `[basis n]`, the same in every process.
2. effect-transaction `open-attempt dir basis`: returns `{:effect/id id}` for the first attempt with no record, skipping
  attempts whose record is `:failed` (the effect definitely did not happen), or `{:effect/existing record}` for the
  first record in any other state. After 64 failed attempts it refuses with `:exhausted`.
3. Merge basis: `[:effect/merge run-id repo pr-number head-sha]`. With an earlier record:
   1. in flight (`:committing`, `:unknown-outcome`): reconciled with GitHub, then reported;
   2. settled (`:succeeded`, `:reconciled`): reported as it is;
   3. `:proposed` but never committed: refused with `:merge-effect-unsettled`.
4. A new head commit is a new basis, so a merge after a rebase starts from attempt 0. A context with no run id still
  gets a random id, and authority refuses it as before.
5. Deploy basis: `[:effect/deploy execution-id kustomize-dir context namespace deployment-name manifest-sha256]`, opened
  after rendering, so a changed manifest is a new effect.
6. Opening a deploy attempt reconciles an earlier one still in flight and answers for it; a settled one is reported with
  evidence from its durable proposal; one proposed but never committed fails at `:proposal`. Only a `:failed` record is
  skipped for the next attempt.
7. Deploy reconciliation settles a record only on a healthy rollout (matched). An unhealthy or failed observation leaves
  the record reconcilable; the provider call stays inside `fx/reconcile!`'s exception handling.
8. Merge reconciliation accepts only a terminal PR state (MERGED or CLOSED); an OPEN PR stays reconcilable. An earlier
  merge attempt is settled before readiness runs, and a PR closed without merging reports `:merge-closed-unmerged`.

Limit: a process that dies after claiming a deploy commit but before applying leaves the old, healthy deployment, which
reconciliation reads as applied. Closing it needs cluster evidence tied to the proposal (the apply stamping the effect
id on the Deployment, read back by the observation), a deploy-provider change proposed separately.

## Testing Plan

1. `merge_effect_id_test`: a second attempt after a pending one asks GitHub instead of merging again (fails on main); a
  failed attempt is retried under a new id; OPEN, OPEN, MERGED runs `gh pr merge` once; a completed merge is reported
  while readiness fails; a PR closed unmerged is reported, not pending, for new and reused records.
2. `deploy_effect_id_test`: a settled deploy is reported with its proposal's evidence and not applied again; a failed
  apply is retried under a new id; an unhealthy rollout is observed again, not re-applied; a throwing observation leaves
  the record reconcilable.
3. `attempt_test`: ids are stable per basis and attempt; `open-attempt` returns attempt 0, the in-flight record, or
  attempt 1 after a failure.
4. Ran locally: pr-lifecycle merge, merge-authority, merge-governed, merge-grant-registry, merge-transaction,
  merge-effect-id, controller, integration tests; phase-deployment deploy-effect-id, deploy-governed,
  deploy-grant-registry, deploy-authority, deploy tests; the deployment-config integration test; effect-transaction
  attempt test. All pass.

## Deployment Plan

No configuration change. Records written by earlier versions keep their random ids and are not matched.

## Related Issues/PRs

Follows #2258 and #2262.

## Checklist

- [x] Regression test fails on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-deterministic-merge-effect-ids.md` (rule 721)
