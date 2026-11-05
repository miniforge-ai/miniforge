<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Give the releaser glob-scoped rules; one glob check for every rule filter

## Overview

Follow-up to #2296, from its post-merge adversarial and rules reviews. The
releaser now gets the standards rules scoped to the files it releases, as the
reviewer does since #2296. Both rule-applicability predicates now share one
glob check, so they agree on what an artifact looks like.

## Motivation

PR #2296 merged on green CI and a clean Copilot review, without the adversarial
and rules reviews the PR process requires. Those reviews, run after merge,
found:

- **Medium:** release built its behavior addendum with no file paths, the bug
  #2296 fixed for review. Five glob-scoped rules that apply in `:release`
  (`:std/bb-over-shell`, `:std/datever`, `:std/specification-standards`,
  `:std/work-spec-authoring`, and `:std/rule-format`, which applies in every
  phase) never reached the releaser.
- `registry.support/rule-applies?` still read only `:artifact/path`, so the two
  applicability predicates disagreed on the artifact shape (dewey 002).
- Review computed its paths inline in a map literal. The common rehydrated route
  listed every path twice. The `:code/file-paths` half had no test (dewey 210, 716).
- The public `filter-applicable-rules` docstrings did not mention
  `:artifact/paths` (dewey 009); a test namespace docstring carried history
  (dewey 600).

## Changes in Detail

1. `policy-pack/registry/support.clj`: `artifact-paths` (an artifact's
   `:artifact/paths`, else its `:artifact/path`) and `artifact-matches-globs?`.
   `rule-applies?` uses it, so it now honours `:artifact/paths` too.
2. `policy-pack/applicability.clj`: `rule-applies-to-artifact?` delegates to
   `artifact-matches-globs?`.
3. `policy-pack/interface.clj`, `interface/checking.clj`: the
   `filter-applicable-rules` docstrings describe `:artifact/paths`.
4. `phase-software-factory/review.clj`: `changed-file-paths`, the distinct
   paths of `:code/files` and `:code/file-paths`.
5. `phase-software-factory/release.clj`: `build-executor-context` takes the
   paths being released and filters the `:release` rules by them;
   `enter-release`, its only caller, passes `release-file-paths` of the
   workflow state.

## Testing Plan

1. `applicability_paths_test.clj`: `:artifact/paths` through
   `registry.support/rule-applies?` (a regression test), and through the
   public `filter-applicable-rules` (contract coverage: it already passed
   after #2296).
2. `review_behavior_addendum_test.clj`: `changed-file-paths` on a rehydrated
   artifact (each path once), a paths-only artifact, and no artifact.
3. `release_test.clj`: release hands `load-and-filter-behaviors` the paths of
   the files it releases.

## Deployment Plan

No configuration or migration. The releaser's prompt grows by the glob-scoped
`:release` rules that match the released files.

## Related Issues/PRs

This follows the review fix in #2296.

## Not in this PR

- Deleted files still select rules (low): the reviewer judges the deletion.
- Paths starting with `./` or `/` miss repo-anchored globs (low, latent): every
  producer emits repo-relative paths from git.

## Checklist

- [x] Regression tests for each behaviour change
- [x] No user-facing strings added
- [x] Stratum metadata consistent in edited namespaces
