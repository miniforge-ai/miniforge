<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(pr-lifecycle): bind merge grants to readiness and head commit; register, re-read, revoke

PR #2258.

## Overview

Merge grants now carry the readiness result and the PR head commit, live in the grant registry, are re-read at commit,
and are revoked when an attempt ends without a commit.

## Motivation

The merge path issued a grant with a hard-coded `:allow` preflight and decided on an empty classification, so issuance
could never refuse an unready merge. `gh pr merge --auto` ran without `--match-head-commit`, so it merged whatever head
the PR had by then. The grant lived only in memory: it was never registered, commit rechecked the copy issuance
returned, and a revoked grant still authorized the merge.

## Changes in Detail

1. `merge_authority/request` takes the evaluated readiness: an unready PR gets a `:deny` preflight and issuance refuses
  it. Each blocking reason also becomes a denying envelope violation.
2. The head commit is read (`gh pr view --json headRefOid`) before readiness runs and bound into the request, the grant
  scope, and the durable proposal. The merge-grant schema requires a full 40- or 64-hex commit id; the merge policy's
  scope keys include `:pr/head-sha`.
3. `gh pr merge` passes `--match-head-commit <sha>` taken from the durable proposal and, with a head bound, no longer
  passes `--auto`: the merge happens now, and GitHub enforces the commit in the merge itself. (`--match-head-commit` is
  checked only when auto-merge is enabled, so a deferred auto-merge could later merge a newer head.) Callers that pass
  no head keep `--auto`.
4. Issuance registers the grant (`grant/register!`); a registration failure refuses the merge.
5. Commit goes through `effect-transaction/commit-current!`, which re-reads the grant from the registry. An unregistered
  grant refuses the commit and leaves the proposal intact; a revoked or expired grant records `:failed`. Neither runs
  `gh pr merge`. The lookup resolves only the grant this attempt issued.
6. The permission check runs before the proposal is written, as deploy already does.
7. Every attempt that ends without a commit (denied, proposal failed, commit refused, commit recorded `:failed`) revokes
  its grant with `:revocation/superseded`. A failed revocation is reported as `:grant/revocation-failure` on the error.
8. A commit refused at the grant recheck reports `:merge-commit-refused`, not `:merge-failed`.

Behavior changes to note:

1. A merge no longer waits in GitHub's auto-merge queue when a head is bound. If GitHub refuses (requirements readiness
  does not check), the attempt fails, revokes its grant, and the next monitor cycle retries under a new grant.
2. A denied merge no longer writes a `:proposed` effect record; the refusal is in the returned error.
3. Revoking one grant does not stop later attempts in the same run; stopping the run does.
4. If the grant directory is unusable (for example a symlinked path component), merges return
  `:merge-authority-unavailable`; the store's reason is in `:authority/result`.

Not in this PR: deterministic per-(run, target) effect ids (next PR), and the same changes for deploy (separate PR).

## Testing Plan

1. `merge_grant_registry_test` (scenario): an unregistered grant, and a grant revoked between issue and commit, both
  refuse the merge before `gh pr merge` runs. On main both tests fail: the merge runs.
2. `merge_authority_test`: an unready merge is refused at issuance; `gh pr merge` argv ends with `--match-head-commit
  <sha>` and has no `--auto`; a grant bound to another head is denied; an unreadable head never merges; failed and
  denied attempts revoke their grants; a failed revocation is reported; a denied merge writes no proposal.
3. `merge_transaction_test`: commit re-reads a grant revoked after issue and records `:failed` without calling the provider.
4. `issuance_test`: the merge grant scope includes the head commit; an abbreviated or missing head commit is refused.
5. Ran locally: execution-grant issuance/interface tests; pr-lifecycle merge, merge-authority, merge-governed,
  merge-transaction, merge-readiness, controller, integration, conflict-resolution, normalize-resolution-shape tests;
  workflow dag-monitor test. All pass.

## Deployment Plan

No configuration change. Grants are stored under `<miniforge-home>/grant-breaches/grants`, the directory issuance
already uses for breach history. The grant store refuses paths with symlinked components, so a `MINIFORGE_HOME` reached
through a symlink now fails merges closed instead of merging unregistered.

## Related Issues/PRs

Part of the effect-authority work: deploy grants and deterministic effect ids follow in separate PRs.

## Checklist

- [x] Regression tests fail on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-merge-grant-head-binding.md` (rule 721), added after merge
