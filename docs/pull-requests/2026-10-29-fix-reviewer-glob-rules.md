<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Give the reviewer the rules scoped to the files implement changed

## Overview

The reviewer now receives the glob-scoped standards rules that apply to the
files implement wrote. Before, review filtered rules with no file path, so
every rule with `:file-globs` was dropped.

## Motivation

Review builds the reviewer's behavior addendum with
`phase/load-and-filter-behaviors :review {:task …}` and no artifact. Rule
applicability (`policy-pack` `rule-applies-to-artifact?`) matches a rule's
`:file-globs` against `(:artifact/path artifact "")`, so with no artifact
every glob-scoped rule failed against the empty path. Rules scoped to
`**/*.clj`, `**/*.py` and the like never reached the reviewer. For semantic
`:warn`/`:audit` rules the reviewer is the only enforcement channel.

## Changes in Detail

1. `rule-applies-to-artifact?` also accepts `:artifact/paths` for an artifact
   that spans several files; the rule applies when any path matches its
   globs. A single `:artifact/path` behaves as before.
2. Review passes the implement artifact's paths (`:code/files` paths and
   `:code/file-paths`) as `:artifact/paths`.

## Testing Plan

1. `applicability_paths_test.clj`: several paths match when any path matches;
   no matching path and no path at all do not match; a single path is
   unchanged.
2. `review_behavior_addendum_test.clj`: review hands
   `load-and-filter-behaviors` the `:review` phase, the task intent, and the
   changed files' paths.
3. Both fail against main and pass with the change; `components/policy-pack`
   and `components/phase-software-factory` suites pass.

## Deployment Plan

No configuration or migration. Reviewer prompts grow by the glob-scoped rules
that apply to the changed files; the reviewer already splits an oversized
addendum across sessions.

## Related Issues/PRs

Draft spec #2053 (N13 delta for rule-graph delivery) measured this gap.

## Not in this PR

Planner and implementer guidance is filtered before any file changes exist,
so glob-scoped rules still reach those agents only through the gate and the
reviewer.

## Checklist

- [x] Regression tests fail before the change and pass after
- [x] No user-facing strings added
- [x] Stratum metadata unchanged in edited namespaces
