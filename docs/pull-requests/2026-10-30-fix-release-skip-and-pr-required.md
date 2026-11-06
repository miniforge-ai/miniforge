<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(release): require the PR when asked for; honor an explicit create-pr? false

## Overview

A release asked to open a PR completed when the release executor reported a
successful publish with no PR, and an explicit `:create-pr? false` in the
execution options was read as absent. This PR fails the first case with
`:release/pr-missing` and honors the second.

The PR first also changed the skip probe to release committed but unpushed
work. `main` fixed that case separately (#2305, #2320, #2333, now in
`release-worktree/committed-since-base`), so that half is removed here; see
Scope change below.

## Motivation

1. `release-publish/publish-prepared` completed any successful publish. A
   publish that pushed the branch but reported no PR, while `:create-pr?` was
   set, ended `:completed` with no PR for the work.
2. `build-publish-context` read `:create-pr?` as
   `(or (get-in ctx [:execution/opts :create-pr?]) (get config :create-pr? true))`.
   An explicit `false` in the execution options fell through to the config
   default, `true`.

## Changes in Detail

1. `publish-prepared`: a successful publish whose release artifact has no
   `:pr-url` while `:create-pr?` is set fails with `:release/pr-missing`
   (logged, message in the phase catalog). Without `:create-pr?` the push
   alone completes, as before.
2. `create-pr-option`: an explicit value in the execution options, `false`
   included, else the phase config's, else `true`.
3. Tests whose `publish-release` stub returned success with no PR now return a
   `:pr-url`: `release_test`'s metrics test and the project tests `handoff`,
   `gate_before_publish` and `artifact_persistence`.

## Scope change

The skip-probe half (`release-unpushed`, `release-unpushed-entries`, the
`release-branch-name` export and their tests) is removed: `main`'s probe
covers the same case. The two Copilot threads on that probe also applied to
`main`'s probe; they are fixed in #2398, and answered here with a link to it.

This branch builds on #2396 (merged as 453eecc6f), the movement-only split
of `release.clj` and `release_test.clj`, because both were over the rule 210
layer budget on `main` and the rescope edits them. It merged `main` after
that squash, so its diff against `main` is the publish change alone.

## Testing Plan

1. `release_publish_test.clj`: `publish-requires-the-pr-it-was-asked-for-test`
   (asked for a PR, none reported: `:release/pr-missing`; not asked: completes)
   and `create-pr-option-test` (an explicit `false` in the options is honored;
   absent, the config's value, else `true`).
2. The release, file-scan, committed-work and checking-commits tests, and the
   project tests that run the release phase.

## Deployment Plan

No configuration or data migration. Takes effect with the next CLI build.

## Related Issues/PRs

Follows #2253 (publish only after the gates pass) and #2261 (push and PR
creation as effect transactions). Builds on #2396. The skip-probe fixes the
Copilot review asked for are in #2398.

## Checklist

- [x] Regression tests fail on the previous code
- [x] Adversarial review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green
