<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(execution-grant): grant requests and policies for push, PR conversation writes, provisioning

PR #2278.

## Overview

Adds issuance for three effect kinds that today run with no grant: pushes, writes to a pull request's conversation, and
`pulumi up`. Two new effect classes, `:effect/comment` and `:effect/provision`; push reuses the existing `:effect/push`.

## Motivation

Only merge, PR creation and deploy had issuance policies and closed request schemas, so every other effect site
(compliance-scanner push and PR, workspace persist push, rebase force-push, conflict-resolution push, policy-eval push,
PR comments and replies, dashboard approve and request-changes, CLI push, provisioning) could not obtain a grant even if
it asked. This PR adds the contracts; the sites move onto them in follow-up PRs.

## Changes in Detail

1. `effect-classes` gains `:effect/comment` and `:effect/provision`.
2. Closed requests (same run, status and preflight fields as the others):
   1. `PushRequest`: `:git/remote-url`, `:git/branch`, `:git/sha` (full commit id), `:git/force?`. The remote is its
     push URL, not a name a later config change could repoint.
   2. `CommentRequest`: `:pr/repo`, `:pr/number`, `:github/action` (`:comment :reply :resolve-thread :review :approve
     :request-changes`), `:github/payload-hash` (SHA-256 of exactly what is written).
   3. `ProvisionRequest`: `:pulumi/stack`, `:pulumi/stack-dir`, `:pulumi/plan-hash`.
3. Issuance policies bind those fields plus `:effect/id` and `:workflow-run/id`, max count 1, not delegable. Push and
  comment grants live 15 minutes, provision 30.

## Testing Plan

`issuance_test`: each new request issues a valid grant whose scope is exactly its target fields plus run and effect id;
dropping any target field is `:invalid-input`; a deny preflight is `:unauthorized`. Ran every execution-grant and
effect-transaction test namespace: 126 tests, 0 failures.

## Deployment Plan

None; nothing requests these grants yet.

## Related Issues/PRs

Part of the effect-authority work. Release push and PR creation are wrapped as effect transactions in #2261; their
grants come later.

## Checklist

- [x] Commits under 200 reportable lines
- [x] PR doc: `docs/pull-requests/2026-10-30-feat-grant-push-comment-provision.md` (rule 721), added after merge
