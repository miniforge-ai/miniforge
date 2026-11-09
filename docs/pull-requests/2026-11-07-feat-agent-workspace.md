<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Multi-repository workspace bound to a PR grant

## Overview

Pilot slice D4 for issue #2439. One task's allowed repositories are cloned into one workspace
inside a leased environment. Each repository gets the same task branch and one pull-request effect,
and the repository set must match a PR grant. API surface class 1: in-process EDN.

## Layer

Application. Depends on the agent-environment contract.

## Motivation

A development-agent task can span several repositories. Each repository's pull request is an
irreversible effect. It must be authorized for exactly that repository and must never be opened
twice when a delivery is retried.

## Changes in Detail

1. `admission`: the allowed set is the `:workspace/repos` set in the scope of a live root
   `:effect/pr-create` grant issued to the acting principal. A repository outside that set is refused
   before any command runs. A scope value that is not a set (nil, a vector, a map) allows nothing.
2. `schema`: a request names repositories by id only. The controller derives each URL from its
   `:repo-base-url` and the id, so a request cannot point an allowed id at another repository. Ids
   cannot contain `.` or `..` parts, base refs cannot start with `-`, and the root must be absolute.
3. `clone`: every repository is cloned to `root/owner/name` (with `--` before the URL and path).
   Its base ref is fetched, so a branch other than the default, a tag or a commit works, and
   `miniforge/task-<task-id>` starts from it. The checkout keeps the requested base ref and the
   commit it resolved to.
4. `delivery`: one effect-transaction record per repository, under a key derived from tenant, run,
   task and repository id. The record is read before any git command, so a retry after the
   environment is gone still returns the recorded outcome, and opens no second PR. A new proposal
   records the task branch's commit (not `HEAD`) and the base ref. Inside the committed effect, that
   recorded commit is pushed to `refs/heads/<task branch>` at the id's URL (not to whatever `origin`
   now names), and the PR port is called with the proposal. Only an answer with a positive PR number
   and a URL succeeds; an anomaly, nil or a malformed answer is `:unknown-outcome`, left for
   reconciliation.
5. `deliver!` takes the path and task branch from the workspace's root and lease. A checkout that
   names a different path or branch, for example `main`, is refused and nothing is pushed.
6. `projects/agent-environments` and the opt-in guard include the new brick; no default project
   depends on it.

## Testing Plan

Over the fake environment, with git answered by a scripted exec:

1. three allowed repositories: three checkouts on one task branch and three PR effects, each
   pushed and opened once; a second delivery returns the recorded PRs and opens none;
2. a repository outside the grant is refused before any git command;
3. a grant of the wrong effect class is refused;
4. clone and push URLs come from the repository id;
5. a handed branch or path is refused with nothing pushed;
6. an id with `..`, a base ref that looks like an option, and a relative root are invalid input;
7. an unanswered, nil or malformed PR answer stays `:unknown-outcome` and is not reopened;
8. a `develop` base is fetched and the task branch starts from FETCH_HEAD; the PR proposal carries
   the base ref and the task branch's commit, and that commit is what is pushed;
9. a second delivery runs no git command;
10. a grant whose `:workspace/repos` is a vector allows nothing.

Not verified: real git against real remotes. Git configuration inside the environment (such as
`url.*.insteadOf`) is not neutralized. Per-repository push credentials are what bound the push
target. A clone retried after a partial clone fails on the existing directory rather than resuming.

## Deployment Plan

Nothing deploys. An operator who opts in supplies the effect store directory, the repository base
URL and the PR port.

## Related Issues/PRs

Issue #2439. Stacked on the Kubernetes provider PR.

## Checklist

- [x] Workspace, admission and delivery with fake-environment tests
- [ ] CI green, Copilot threads resolved, squash-merged
