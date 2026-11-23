<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(phase-deployment): require dry-run evidence; register, re-read, and revoke deploy grants

PR #2262.

## Overview

Deploy grants now require recorded dry-run evidence, live in the grant registry, are re-read at commit, and are revoked
when a deploy ends before it reaches the cluster. A cancelled run no longer applies.

## Motivation

The deploy request hard-coded an `:allow` preflight. The grant lived only in memory: it was never registered, commit
rechecked the copy issuance returned, and a grant revoked after issue still authorized the apply. A run cancelled while
the deploy phase ran still applied, and an abandoned deploy left its grant live until expiry.

## Changes in Detail

1. `deploy_authority/preflight-result`: `:allow` only when deployment policy allowed and the rendered manifests passed a
  server dry run. Issuance refuses a deploy with no recorded dry run.
2. Issuance registers the grant (`grant/register!`); a registration failure refuses the deploy.
3. Commit goes through `effect-transaction/commit-current!`, which re-reads the grant from the registry with the
  context's `:clock` (else the system clock). An unregistered grant refuses the commit; a revoked or expired grant
  records `:failed`. Neither applies. The lookup resolves only the grant this attempt issued.
4. Right before commit, the deploy reads the run's control state (`:control-state` on the context or in
  `:execution/opts`). A stopped run fails the deploy without applying.
5. A deploy that ends with no transaction, or with one left `:proposed` or `:failed`, revokes its grant:
  `:revocation/operator` when the run was stopped, else `:revocation/superseded`.

Not in this PR: deterministic per-(run, target) effect ids (next PR).

## Testing Plan

1. `deploy_grant_registry_test` (scenario): an unregistered grant, and a grant revoked between issue and commit, both
  refuse the deploy before anything is applied. Both fail on main: the deploy applies.
2. `deploy_authority_test`: a deploy without dry-run evidence (absent or blank) is refused at issuance; prepare
  registers its grant.
3. `deploy_governed_test`: a grant expired by commit time never applies; a cancelled run never applies and revokes with
  `:revocation/operator`; a failed apply revokes with `:revocation/superseded`.
4. `deploy_manifest_policy_test` and the `deployment_config_integration_test` project test now pass dry-run evidence and
  canonical grant dirs.
5. Ran locally: phase-deployment deploy-authority, deploy-governed, deploy-grant-registry, deploy-manifest-policy,
  deploy, home-paths tests; the deployment-config integration test. All pass. The new tests fail on main (15 failures, 1
  error).

## Deployment Plan

No configuration change. Grants are stored under `<miniforge-home>/grant-breaches/grants`. The grant store refuses paths
with symlinked components, so a `MINIFORGE_HOME` reached through a symlink now fails deploys closed.

## Related Issues/PRs

Same change for merges: #2258.

## Checklist

- [x] Regression tests fail on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-deploy-grant-registry.md` (rule 721), added after merge
