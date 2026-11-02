<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs: Bring three 2026-11-01 PR docs to the 721 sections

## Overview

The PR docs for #2303, #2307 and #2319 move from ad hoc
Problem / Changes / Testing headings to the sections that
pr-documentation (dewey 721) requires. They also record what happened
after #2303 merged.

## Motivation

Those three PRs merged before their docs had been checked against 721.
The operator asked for the old docs to be fixed. Filenames are unchanged.
Repo practice keeps a descriptive slug when the branch name carries no
meaning; #2296's doc is an example. #2303's branch was
`claude/thesium-budget-intelligence-fpln6l-7m0gyo`, which is unrelated to
its change.

## Changes in Detail

- `2026-11-01-fix-gate-warnings-survive-retries.md` (#2303) now has the
  721 sections, describes the merged code as it is (the warnings clause
  sits in `apply-gate-validation`'s outer `cond->`), and records under
  Related that it merged without CI re-running against #2311, broke main,
  and was fixed by #2319 and trimmed by #2316.
- `2026-11-01-gate-messages-to-catalog.md` (#2307) now has the 721
  sections. Its assertion count is updated from 109 to 110, measured on
  main. Related records the three Copilot rounds.
- `2026-11-01-fix-gate-warnings-test-durable-sink.md` (#2319) now has the
  721 sections, with the same content.

## Testing Plan

Documentation only, so per dewey 716 there are no tests. Gate 1 for a
docs-only change checks every factual claim against the repo:

- merge SHAs and timestamps were taken from the GitHub API
- the 110-assertion count comes from running `messages-test` on main
- the `cond->` clause was read on main

markdownlint is clean against the repo's `.markdownlint.jsonc`.

## Deployment Plan

None.

## Related Issues/PRs

- #2303, #2307, #2319: the PRs whose docs change.

## Checklist

- [x] Every claim checked against the repo
- [x] markdownlint clean
- [ ] CI green
- [ ] Copilot review resolved
