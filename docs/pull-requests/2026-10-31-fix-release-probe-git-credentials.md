<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(release-executor): give the push probe's ls-remote the git credential

## Overview

The release's push effect transaction confirms an uncertain push with `git ls-remote origin`
inside the capsule (`sandbox/remote-branch-sha!`). That `ls-remote` now asks the executor for
git credentials (`:git-credentials? true`). The probe's local `git remote get-url` checks do
not ask for them.

## Motivation

Origin in the capsule holds no credential. On a private HTTPS remote the probe's `ls-remote`
therefore fails. A push that crashed, timed out or was interrupted after it reached origin is
then reported `:push-unresolved`, even though the branch was published.

`:git-credentials?` is the executor option the capsule credential work (#2139) adds. With it,
the OCI executor gives that one command the recorded remote's credential as per-command git
config. #2139 makes the release's push and fetches ask for it. Until #2139 merges, executors
on main ignore the option, so this change takes effect when it lands.

## Changes in Detail

1. `sandbox/remote-branch-sha!`: the `ls-remote` runs with `(assoc exec-opts :git-credentials? true)`.
2. `fake-remote`: with `:ls-remote-needs-credentials?` in its state, a `git ls-remote` whose opts
   lack `:git-credentials?` fails without effect, as against a private HTTPS origin.
3. Tests:
   1. `crash-after-the-push-to-a-private-origin-test` (crash matrix): the push reaches origin,
      the process dies, and the resume's probe must authenticate to find the branch.
   2. `push-probe-ls-remote-asks-for-git-credentials-test` (sandbox): only the `ls-remote` asks
      for credentials.

## Testing Plan

1. Both new tests fail without the fix and pass with it.
2. Every release-executor test namespace, run under the shared JVM lock.

## Deployment Plan

Ships with the next release. It takes effect once #2139 lands.

## Related Issues/PRs

1. Stacked on the mechanical split of `sandbox.clj` (rule 210), which this change needs to pass
   stratum-lint.
2. #2139: the executor-side `:git-credentials?` and the review thread that found this gap.

## Checklist

- [x] Tests fail without the fix
- [ ] Release-executor test namespaces pass
- [ ] CI green, Copilot comments resolved
