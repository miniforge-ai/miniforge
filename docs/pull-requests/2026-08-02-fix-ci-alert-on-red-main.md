<!--
  Title: Alert when CI fails on a push to main
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(ci): alert when main goes red

## Overview

Adds a `main-status` job to `.github/workflows/ci.yml`. On a push to `main`,
it opens (or comments on) a tracking issue when any CI job failed, and closes
that issue on the next fully green run. Push events on `main` only — pull
request runs are unaffected.

## Motivation

`ci.yml` had no `failure()` handler and no notification step anywhere.
Verified 2026-08-02: no `notify`, `slack`, `issues.create`, or `failure()`
appeared in the file, and none of the other workflows (`bump-standards.yml`,
`notify-website.yml`, `pr-size.yml`, `refresh-cost-table.yml`, `release.yml`)
covered CI outcomes.

Consequence: `main`'s Test job was red across at least six consecutive merges
(runs `30734048427` through `30735684110`) and produced zero signal. It was
red for two independent reasons, and the second had gone entirely unnoticed.
Both were fixed in [#1616](https://github.com/miniforge-ai/miniforge/pull/1616)
(commit `f624b6f`), but the reason nobody knew was left unaddressed. This PR
addresses it.

### Why an issue and not a chat message

There is no chat webhook configured. Checked before choosing: repository
secrets are `HOMEBREW_APP_ID` and `HOMEBREW_APP_PRIVATE_KEY`; the organization
secrets visible to this repo are `CI_BOT_WRITE_APPID`, `CI_BOT_WRITE_PEM`,
`MINIFORGE_CI_BOT_APPID`, and `MINIFORGE_CI_BOT_KEY`. No Slack or Discord
webhook exists at either level.

A GitHub issue needs no new secret, reaches every repo watcher through the
notification path they already use, and gives the failure a place to live
until it is fixed. If a webhook is added later, it slots into the same job as
an extra step.

### Why this and not carrying "previously failing" tests into PR runs

PR CI runs `bb test` (`scripts/test-since-stable.bb`, changed-and-affected)
while main pushes run `bb test:all`, so namespaces outside a PR's affected set
never run on that PR. That selection behaved correctly in this incident — none
of the six PRs touched the affected components.

Carrying a "previously failing" set forward into PR runs is the other option
on the table, but it needs persisted state and still does nothing about a
first break going unseen. Alerting is the higher-value change to make first.
Test selection is deliberately untouched here.

## Changes in Detail

### `.github/workflows/ci.yml`

One new job, `main-status`:

- `needs: [structure, lint, test, test-windows, build]` — every job in the
  workflow, so a failure anywhere is caught.
- `if: !cancelled() && github.event_name == 'push' && github.ref ==
  'refs/heads/main'`. PR failures are already visible on the PR itself, so
  they are excluded. `!cancelled()` keeps a manual cancellation from being
  reported as breakage. Green runs are included so the issue can be closed.
- `permissions: {issues: write, actions: read}` — narrower than a workflow
  default would be, and job-scoped. The rest of the workflow keeps
  `contents: read`.
- `concurrency: {group: main-status-alert, cancel-in-progress: false}` —
  serializes alert jobs across overlapping main runs, so two failures landing
  close together cannot each conclude that no issue exists and file one.

The step itself decides an outcome from `join(needs.*.result, ' ')`:

| Results | Outcome | Action |
|---|---|---|
| any `failure` | red | comment on the open tracking issue, or create it |
| all `success` | green | close the open tracking issue, if any |
| anything else (`cancelled`, `skipped`, no failure) | inconclusive | nothing |

The inconclusive branch matters: a run with no failure but a non-success
result is not evidence that `main` is healthy, so it must not close the issue.

### Deduplication

Open issues are found through `GET /repos/{repo}/issues?state=open&labels=ci-main-red`,
not `gh issue list --search`. The search index is eventually consistent, so a
freshly created issue can be invisible to the next run's lookup, which is
exactly the case that would produce a duplicate. `/issues` also returns pull
requests, hence the `.pull_request == null` filter.

The `ci-main-red` label does not exist in the repo yet; the job creates it on
first use, idempotently (`|| true`), rather than requiring an out-of-band repo
change that would not appear in this diff.

### No checkout, no toolchain

The job deliberately has no `actions/checkout` and no Java/Clojure/bb setup.
The alerter has to survive the failure it reports: a bb- or Clojure-based
alerter would be taken out by exactly the deps or toolchain breakage it exists
to announce. `gh` is preinstalled on GitHub-hosted runners, so the step needs
nothing but `GH_TOKEN` and `GH_REPO`.

This is why `workflows/bb-over-shell` (dewey 740) does not apply: the rule
scopes to `bb.edn` and `scripts/**/*.sh` for build, launch, package, lint,
test, and dev-loop tasks, and its "bb is genuinely unavailable" exception is
the operative condition here.

### `docs/development/ci-debugging.md`

New "Alerting on a red `main`" section describing what the issue is, when it
opens and closes, and how to silence it.

## Testing Plan

The step script was extracted from the parsed YAML and exercised against a
stub `gh` across every branch:

1. red, no open issue → creates the label, then the issue.
2. red, open issue → comments on it, creates nothing.
3. green, open issue → closes it.
4. green, no open issue → no calls beyond the lookup.
5. inconclusive (`cancelled` present, no `failure`) → no write calls; the open
   issue is left open.
6. red with the jobs API failing → still files the issue, with
   `Failed jobs: unknown, see the run`.

All six behaved as specified. The YAML was parsed to confirm the heredoc
terminator lands at column 0 after block-scalar de-indentation.

On this PR the job should report as skipped — `github.event_name` is
`pull_request` — which is also the check that the `if` expression parses.
Live confirmation of the green path comes on the first push to `main` after
merge.

## Deployment Plan

Merging is the deployment. The first `main` push after merge exercises the
green path (lookup, no issue, exit 0). The red path stays untested against the
real API until `main` next breaks, which is the intended trigger.

Rollback is deleting the job; nothing else in the workflow depends on it.

## Security Considerations

No untrusted input reaches the shell. The step interpolates only
`github.token`, `github.repository`, and `join(needs.*.result, ' ')` — all
GitHub-controlled. `GITHUB_ACTOR` and `GITHUB_SHA` are read from the runner
environment and written into a markdown file, never executed. No commit
message, PR title, or issue body is interpolated, so the workflow-injection
class described in
<https://github.blog/security/vulnerability-research/how-to-catch-github-actions-workflow-injections-before-attackers-do/>
does not arise.

`permissions` is set at job level and grants `issues: write` and
`actions: read` only.

## Related Issues/PRs

- [#1616](https://github.com/miniforge-ai/miniforge/pull/1616) — fixed the two
  failures that went unnoticed; this PR fixes the not-knowing.

## Checklist

- [x] Alert restricted to `main` pushes, not PRs
- [x] Deduplicated — one open issue reused, further failures become comments
- [x] Issue closes automatically when `main` goes green
- [x] Checked for an existing Slack/webhook secret before choosing the channel
- [x] `set -o pipefail` on the Run tests step left untouched
- [x] Test selection left untouched
- [x] All script branches exercised against a stub `gh`
- [x] `docs/development/ci-debugging.md` updated
