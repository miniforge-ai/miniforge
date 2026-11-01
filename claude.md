<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Instructions for this repo

**Canonical agent instructions:** See [agents.md](agents.md).

## PR merge gate (every session, every PR)

Open PRs ready for review, never as drafts: Copilot reviews only non-draft PRs.

A PR is ready to merge only when all four gates pass, in this order:

1. **Adversarial review** of the diff per
   `standards/miniforge/workflows/code-review-rigor.mdc` (dewey 720).
2. **Rules review** against every applicable rule in `standards/miniforge/`
   (start from its `agents.md` and `index.mdc`).
3. **CI** green on the current head.
4. **Copilot review** findings fixed or answered, threads resolved.

When all four pass, the session that owns the PR merges it. Do not wait for the
operator to click merge. Green CI plus a clean Copilot review is not enough on
its own. See [agents.md](agents.md#pr-merge-gate).
