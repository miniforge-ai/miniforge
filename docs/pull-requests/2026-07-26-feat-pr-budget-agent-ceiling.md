<!--
  Title: Decouple pr-budget's ceiling from commit-budget's, raise to 600
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: raise pr-budget's ceiling to 600, decoupled from commit-budget's 200

## Overview

Follow-up to [#1539](https://github.com/miniforge-ai/miniforge/pull/1539)
(same-day). `bb pr-budget`'s default ceiling (reportable lines, after
blank/comment/generated-path exclusion — not raw diff lines) moves
from 200 (a plain alias of `commit-budget/default-budget`) to its own,
independent 600.

## Motivation

The 200-line commit ceiling is Smartbear 2010 / Cisco 2018 human
review-throughput research — reviewer defect-detection drops sharply
past ~200 LOC for a human. There is no equivalent published research
for LLM-driven review, and this codebase is overwhelmingly agent-
written and agent-reviewed, not the human-commit-by-commit case that
number was calibrated for. Reusing the human number for the PR-level
gate was a reasonable placeholder for #1539's first cut, but not
actually justified — user feedback directly: "we should set that to
whatever is optimal for the agent context... I'm pretty sure it's not
74 files and 70,000 lines."

600 is a reasoned estimate, not a citation:

- 3x the commit-level ceiling — a PR bundling a small, coherent
  handful of commit-sized steps, not a monolith.
- An order of magnitude above the human number — agents don't suffer
  literal per-line reading fatigue the way human reviewers do.
- Two orders of magnitude below where things actually broke down this
  session: GitHub Copilot's automated review engaged fully at 9,906
  raw changed lines but silently declined to comment at all at 12,592
  (confirmed empirically, both PRs from the same stratum-lint Wave 1
  program), and a dedicated adversarial review pass at that scale cost
  170-210k tokens per PR and still needed follow-up rounds to converge.

## Changes in Detail

- `tasks/pr_budget.clj`: `default-budget` is now a plain `600`, no
  longer `cb/default-budget`. Namespace docstring and the def's own
  docstring both explain the reasoning above. The CI failure message's
  "Why this exists" section drops the now-inapplicable "~200 LOC"
  human-research line and replaces it with the actual empirical
  Copilot-threshold and adversarial-review-cost numbers from this
  session.
- `agents.md`: "PR Discipline" bullet now states both numbers (commits
  <200, whole PRs <600) and points at the `## PR Size and Review
  Discipline` section for why they differ; that section gained a "Why
  600, not 200" note and the "Why both, together" paragraph now cites
  the specific 9,906/12,592 empirical data point instead of "past its
  own undocumented size ceiling."
- `.github/workflows/ci.yml`: updated the top-of-file comment's stated
  number to match.

## Testing Plan

- Re-ran the same 3 manual scenarios from #1539 against the new
  default: small PR (4 reportable lines) passes clean, a Wave 1
  mechanical-fix PR (1618 reportable lines) is correctly rejected at
  the new 600 ceiling, override still works.
- `clj-kondo --lint tasks/pr_budget.clj`: 0 errors, 0 warnings.
- `markdownlint agents.md`: 0 errors.
- Validated `.github/workflows/ci.yml` is well-formed YAML.
- Adversarially re-read the full diff before pushing (per the rule
  #1539 itself introduced) — confirmed `commit-budget` (`cb`) is still
  genuinely used elsewhere in the file (`parse-diff`,
  `reportable-changes`, `total-lines`, `print-report!`), so decoupling
  `default-budget` doesn't leave a dead require; confirmed the
  rewritten CI failure message still reads correctly end-to-end.

## Deployment Plan

Merges to `main`. Takes effect on the next PR's `pull_request` event.
No ruleset change needed (this check still isn't a required status
check, per #1539's own deployment plan).

**Not the end of the calibration, by design.** The user's own framing:
not just validate 600, but converge on the actual optimal ceiling —
which may differ **per model and/or per orchestrator design** (a
single-agent review pass vs. a multi-agent workflow with parallel
finders and adversarial verifiers may tolerate meaningfully different
diff sizes before quality degrades). Planned next step: use Mini Bench
to actually measure this empirically across configurations, once the
current round of work is done, rather than treating 600 as a
permanent number. Tracked in project memory.

## Related Issues/PRs

- Follows [#1539](https://github.com/miniforge-ai/miniforge/pull/1539)
  (same-day), which introduced `bb pr-budget` with the initial 200
  placeholder.
- Existing commit-level gate, unchanged: `tasks/commit_budget.clj`.

## Checklist

- [x] Re-verified all 3 manual test scenarios against the new default
- [x] `clj-kondo` clean
- [x] `markdownlint` clean
- [x] CI workflow YAML validated as well-formed
- [x] Adversarially self-reviewed before pushing, per the standing rule
- [x] No `standards/miniforge` submodule changes
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
