<!--
  Title: Add a PR-level size budget and an adversarial-review-before-push rule
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: PR-level size budget + adversarial-review-before-push standing rule

## Overview

Adds `bb pr-budget`, a CI-only gate that rejects a whole pull request's
merge-base..head diff once it exceeds 200 reportable lines (same
blank/comment/generated-path exclusions as the existing commit-time
`bb commit-budget`), wired into `.github/workflows/ci.yml` as a new
`pr-size` job that runs only on `pull_request` events. Also documents
"review your own diff adversarially before pushing" as a standing
principle in `agents.md`, alongside a new `## PR Size and Review
Discipline` section explaining why both exist together.

## Motivation

The 2026-07 stratum-lint Wave 1 remediation program (tracked in
`work/stratum-lint-baseline-2026-07-24.md`) shipped several PRs of
74-98 changed files and 10,000+ changed lines each, every one as a
single `MINIFORGE_COMMIT_BUDGET_OVERRIDE`'d commit — perfectly legal
under the gate that existed, since `commit-budget` only ever looked at
one commit's staged diff, never a PR's cumulative shape across however
many commits it took to land. Two consequences surfaced directly:

1. **GitHub Copilot's automated review silently declined to comment at
   all** on the largest of these PRs (confirmed directly: zero inline
   comments posted on a 74-file PR, despite being asked to review) —
   past some undocumented size ceiling, a gargantuan PR gets
   effectively zero automated review coverage, with no visible warning
   that this happened.
2. A dedicated adversarial review pass, run by hand after several of
   these PRs had already merged, found real defects plain CI (lint +
   tests green) had not and could not catch: a trailing comment
   silently displaced onto the wrong test form during an automated
   reorder, and — more seriously — a PR whose description claimed
   "mechanical, no logic changes" while actually carrying an
   undisclosed behavior change (a genuine bug fix folded into the same
   commit as the mechanical rewrite it was found alongside).

Passing CI was never sufficient evidence of correctness for a diff
that size; treating it as sufficient is what let both of the above
ship unreviewed. This PR closes the gate side (a PR that large can no
longer merge without either being split up or explicitly flagging its
own size with a reviewable rationale) and documents the practice side
(review your own diff like a skeptical reviewer before pushing, not
just "tests pass").

## Changes in Detail

- `tasks/pr_budget.clj` (new): PR-level counterpart to
  `tasks/commit_budget.clj`. Reuses `commit-budget`'s pure line-
  counting/exclusion functions directly (`parse-diff`,
  `reportable-changes`, `total-lines`, `print-report!`) rather than
  duplicating them, so a file that doesn't count against a commit
  doesn't count against its PR either. Adds:
  - `pr-diff`: `git diff --no-color -U0 <base>...<head>` (three-dot,
    against the merge-base — matches what GitHub's own PR diff view
    and `gh pr diff` report).
  - `override-rationale`: looks for a `MINIFORGE_PR_BUDGET_OVERRIDE:
    <rationale>` line in the PR description (not an env var, since a
    CI job has no access to the author's local shell — a marker in
    the PR body is visible to every reviewer on the PR, not just
    whoever committed).
  - `check-pr-budget!` / `run`: reads `PR_BASE_SHA` / `PR_HEAD_SHA` /
    `PR_BODY` from the environment (set by the calling CI step from
    the `pull_request` event payload).
- `bb.edn`: new `pr-budget` task, deliberately NOT added to the
  `pre-commit` `:depends` chain — it needs `PR_BASE_SHA`/`PR_HEAD_SHA`,
  which don't exist locally; it's CI-only by design.
- `.github/workflows/ci.yml`: new `pr-size` job, gated on
  `github.event_name == 'pull_request'` (a push to `main` has no
  meaningful base..head PR diff — that's what `commit-budget` already
  covers, per commit). Uses `fetch-depth: 0` so the merge-base is
  actually present locally. Passes `PR_BODY` via `env:`, not
  interpolated into the `run:` shell string, per this repo's workflow-
  injection-safety convention (untrusted PR-description text goes
  through an environment variable, never directly into a shell
  command).
- `agents.md`: updated the existing "PR Discipline" bullet's stale
  `<400 lines` aspiration to the actual enforced `<200 lines` ceiling;
  added a new "Adversarial Review Before Push" principle; added the
  `## PR Size and Review Discipline` section documenting the incident
  and the reasoning above, so future agents reading this file get the
  "why," not just the "what."

## Testing Plan

- `clj-kondo --lint tasks/pr_budget.clj tasks/commit_budget.clj`: 0
  errors, 0 warnings.
- Ran `bb pr-budget` locally against three real merge-base..head
  ranges (fetched the relevant commits first):
  1. A genuinely small PR (#1537, a 1-file/2-line comment fix):
     `📏 pr-budget: 4 / 200 lines OK` — passes clean.
  2. A Wave 1 mechanical-fix PR (#1528, `operator`, one of the
     *smallest* in that program): `❌ PR BUDGET EXCEEDED: 1618 > 200
     lines` — correctly rejected. Confirms even the smallest Wave 1
     PRs would have tripped this gate had it existed at the time.
  3. Same #1528 diff with `PR_BODY` containing
     `MINIFORGE_PR_BUDGET_OVERRIDE: mechanical Wave 1 stratum-lint fix,
     whole-component rewrite`: `📏 pr-budget: OVERRIDDEN (1618
     reportable lines)`, exit 0 — override mechanism works.
- Validated `.github/workflows/ci.yml` is well-formed YAML
  (`python3 -c "import yaml; yaml.safe_load(...)"`).
- `markdownlint agents.md` and the new PR doc: 0 errors (two
  bullet-list line-length violations from the first draft, wrapped to
  fix).
- Did not modify `standards/miniforge` (a submodule, separate
  governance) — the pre-existing "See `standards/miniforge/CLAUDE.md`
  for authoritative descriptions" pointer in `agents.md` is unchanged;
  this PR only updates the summary bullets and adds a new section in
  the consuming repo's own `agents.md`.

## Deployment Plan

Merges to `main`. The `pr-size` CI job starts running on the next
`pull_request` event automatically (no ruleset change needed to run
it) but is **not required** in the repo's branch protection ruleset as
part of this PR — recommend making it a required status check as a
deliberate, separate follow-up once it's been observed passing/failing
correctly on a few real PRs, rather than making it blocking on day
one. Every future PR whose diff exceeds 200 reportable lines will need
either splitting or an explicit `MINIFORGE_PR_BUDGET_OVERRIDE:` line —
this includes the remainder of the stratum-lint Wave 1 program, which
will need to shift from "one PR per component" to smaller per-file or
per-file-group slices going forward.

## Related Issues/PRs

- Motivated by user-requested adversarial review of
  [#1534](https://github.com/miniforge-ai/miniforge/pull/1534) (98
  files) and [#1535](https://github.com/miniforge-ai/miniforge/pull/1535)
  (74 files) from the stratum-lint Wave 1 program, and a follow-up fix
  from that review: [#1537](https://github.com/miniforge-ai/miniforge/pull/1537).
- Existing commit-level gate this mirrors: `tasks/commit_budget.clj`
  (added 2026-05-02, `141840af3`).
- Tracked in `work/stratum-lint-baseline-2026-07-24.md`'s program —
  Wave 1 batch 7 is paused pending this landing.

## Checklist

- [x] `clj-kondo` clean on both budget task files
- [x] Manually verified against 3 real PR diff ranges: small-clean-pass,
      large-reject, large-with-override
- [x] CI workflow YAML validated as well-formed
- [x] `markdownlint` clean on both changed/added Markdown files
- [x] No `standards/miniforge` submodule changes
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
