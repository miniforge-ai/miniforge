<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(pr): add PR documents for five merged effect-authority PRs

PR #2281.

## Overview

Adds the `docs/pull-requests` documents for five effect-authority PRs that merged without them, and this PR's own
document. The five are PRs #2258, #2262, #2265, #2276 and #2278.

## Motivation

Standards rule 721 (`workflows/pr-documentation.mdc`) requires one document per feature branch, named by PR creation
date and branch. Its only exemptions are `main`/`nonprod`, trivial typo fixes and automated dependency updates. The five
PRs merged without the file.

## Changes in Detail

1. Five files under `docs/pull-requests/`: `2026-10-30-fix-merge-grant-head-binding.md`,
  `2026-10-30-fix-deploy-grant-registry.md`, `2026-10-30-fix-require-acting-operator.md`,
  `2026-10-30-fix-remove-ungoverned-auto-merge.md`, `2026-10-30-feat-grant-push-comment-provision.md`.
2. Each file is the repo's Markdown header, the PR title as a heading, a `PR #n.` line, then the PR's final description
  with two edits: the checklist line names the file, and the generated-by footer is dropped.
3. `2026-10-30-docs-track-d-pr-docs.md` for this PR. Its lines are wrapped by hand so no wrapped line starts with a PR
  number, which the Markdown formatter would turn into a heading.

## Testing Plan

Documentation only. The commit hooks passed. A read-only review compared each file with its PR's final description.

## Deployment Plan

None.

## Related Issues/PRs

PRs #2258, #2262, #2265, #2276 and #2278.

## Checklist

- [x] PR doc: `docs/pull-requests/2026-10-30-docs-track-d-pr-docs.md` (rule 721)
