<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(phase-software-factory): split release.clj and release_test.clj (rule 210)

## Overview

`phase_software_factory/release.clj` used 6 stratum layers and
`release_test.clj` 5, against the rule 210 budget of 3. Any commit that staged
either file failed the pre-commit SL003 gate. This PR moves their definitions,
unchanged, into namespaces of at most 3 layers each. The release fixes that
need these files can land without a stratum override.

## Motivation

Two Copilot findings on #2264 apply to `main`.
The host git probes inherit `GIT_DIR` / `GIT_WORK_TREE` from a git hook.
A resume with no recorded task
base measures from the checkpoint and skips unpublished work.
Both fixes edit
the release scan in `release.clj`, and rescoping #2264 edits
`release_test.clj`. stratum-lint on `main`:

```text
release.clj:769:1: SL003 file uses 6 distinct layers (max 3); split the namespace or extract a component
release_test.clj:936:1: SL003 file uses 5 distinct layers (max 3); split the namespace or extract a component
```

## Changes in Detail

1. `release-worktree` (new, 3 layers): what the release finds in the worktree.
   `commit-sha-pattern`, `porcelain-scan-entry`, `scan-io`,
   `porcelain-z-entries`, `zero-files-data`, `release-base-shas`,
   `git-dirty-files`, `git-dirty-files-capsule`, `release-files-result`,
   `committed-since-base`, `dirty-scan`, `release-files!`.
2. `release-state` (new, 2 layers): what the release reads from the run.
   `log-already-implemented!`, `ctx-worktree-path` (with the comment block
   above it), `capsule-target`, `rehydrate-committed-files`,
   `unresolved-known-issues`, `readiness-check`, `release-readiness`,
   `skip-probe`, `build-workflow-state`.
3. `release` (3 layers) keeps the phase interceptor: `attach-consultation`,
   `default-config`, `compute-verdict`, `attach-error-verdict`,
   `release-file-paths`, `build-executor-context`, `leave-release`,
   `error-release`, `enter-release`, the `get-phase-interceptor-method`
   method and the defaults registration. It still registers the `:release`
   phase on load, so `interface.clj`, `namespaces.edn` and the tests that
   require it for registration are unchanged.
4. `release-test-support` (new test namespace, 3 layers): the fixtures and
   context builders `release-test` used (temp worktrees, mock implement
   results, base contexts, the capturing logger, readiness helpers).
   `release-test` (2 layers) keeps its tests, the two helpers only it uses,
   and its fixtures, and refers to the moved helpers by name.
5. Edits outside the moved bodies:
   1. Calls that now cross a namespace boundary are alias-qualified
      (`release-worktree/dirty-scan`, `release-state/skip-probe`,
      `release-state/release-readiness`, and in the tests
      `release-worktree/release-files-result`).
   2. Ten private definitions became public because another namespace calls
      them: `committed-since-base`, `dirty-scan`, `release-files!`,
      `skip-probe`, and the test helpers `capturing-logger`, `entry-events`,
      `sh-must-succeed!`, `readiness-ctx`, `review-decided`,
      `write-and-commit-mock-files!`.
   3. Layer headings and `^{:stratum n}` values are regenerated for each file;
      non-layer dashed banners in the test file became `;;` comments.
   4. `release_file_scan_test.clj` reads `git-dirty-files` and
      `git-dirty-files-capsule` from `release-worktree`.

## Movement check

`split-compare` is the script used for the oci_cli and implementer splits.
It compares every top-level `def` form's body in `main`'s `release.clj`,
`release_test.clj` and `release_file_scan_test.clj` with the new files.
Metadata is stripped, and aliases into the `release*` namespaces are normalized:

```text
base defs 110 | new defs 110 | identical 110 | changed 0 | removed 0 | added 0
```

The only kind changes are the ten `defn-` to `defn` above. stratum-lint reports
no findings for the six files; clj-kondo reports 0 errors and 0 warnings.

## Testing Plan

The validation ran 110 tests with 6 failures, all in the CLI config-schema
source scan. It covered phase-software-factory release tests: release, file scan,
publish, committed work, checking commits, and artifact persistence.
It also covered the phase `done` test and project release-phase tests:
gate before publish, runner integration, handoff, and artifact persistence.
All failures are in
`schema-covers-every-user-config-access-in-src-test`. They name accesses in
`review.clj`, `verify.clj`, `phase/done.clj` and `release_publish.clj`, none
of which this PR changes, so they fail the same way on `main`. None of the
split files is reported.

## Deployment Plan

No behaviour change and no migration.

## Related Issues/PRs

Unblocks the release-scan fix PR stacked on this one, which answers two
Copilot threads on #2264, and the rescope of #2264 to its publish half.

## Checklist

- [x] Movement check: 110 of 110 definitions identical
- [x] stratum-lint and clj-kondo clean
- [x] Release tests pass (the schema-scan failures are on `main` too)
- [ ] CI green, Copilot comments resolved
