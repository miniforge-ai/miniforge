<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(n13): Comment Response Agent — policy-eval (deterministic) path

## Overview

Closes the last automation gap inside the N13 standards-review loop.
When the Standards Reviewer (#818) posts violations and the operator
runs `bb miniforge pr policy-respond <pr-url>`, this agent now:

1. Filters comments to those authored by `miniforge-policy-evaluator[bot]`
2. Parses the embedded `:comment/payload` EDN block per N13 §2.3
3. Applies `:violation/suggested-fix` deterministically at the
   comment's `(path, line)` — no LLM call
4. Stages, commits, pushes
5. Replies on each fixed thread with the commit SHA + diff snippet
6. Resolves the conversation

Distinct from `bb miniforge pr respond <pr-url>`, which uses
LLM-driven fix generation for general human review comments. This
command targets only the bot we own; the structured payload makes
the LLM round-trip unnecessary for the common case.

## Why this matters

After [#837](https://github.com/miniforge-ai/miniforge/pull/837)
(`pr review-monitor`) and [#848](https://github.com/miniforge-ai/miniforge/pull/848)
(`pr resume-dispatch`), the only remaining manual loop in N13's
auto-trigger arc was: **operator runs `pr respond` to address
standards violations**. With this PR, the policy-eval branch becomes
deterministic and operator-free for single-line auto-fixable hints.

The next inbound class of operator turns the mining flagged
(`respond-to-comments` ~80 instances) is now retired for the
standards-pack subset.

## Scope (v0)

| Comment shape | Behavior |
|---|---|
| `:violation/auto-fixable? true` + single-line `:violation/suggested-fix` | **Apply**: deterministic patch, commit, reply, resolve |
| Multi-line `:violation/suggested-fix` (contains `\n`) | **Escalate**: skipped with `:policy-eval/multi-line-not-supported`, surfaced in `:escalated` for operator |
| `:violation/auto-fixable? false` | **Escalate**: surfaced with `:violation/auto-fixable?-false` reason |
| Blank `:violation/suggested-fix` | **Escalate**: `:no-suggested-fix` |
| Body without parseable payload | **Skip**: `:no-payload` |

Multi-line / patch-hunk-shaped fixes are deferred to v1 — applying
them requires knowing the original span, which the comment doesn't
carry directly. v1 can use `:current` to compute the span.

## What's new

### `components/pr-lifecycle`

- `policy_eval_responder.clj` (new):
  - **Layer 0**: `policy-eval-author` constant + `policy-eval-comment?` predicate + `extract-policy-payload` (delegates
    to compliance-scanner)
  - **Layer 1**: `classify-fix` (pure decision: apply / escalate / skip), `plan-fixes` (partition by action)
  - **Layer 2**: `apply-single-line-replacement!` (file I/O with typed errors for missing-file / out-of-range / I/O
    failure), `materialize-fix!` (decorates with comment-id)
  - **Layer 3**: `commit-and-push!`, `reply-and-resolve-fixed!`, `respond-to-policy-comments!` (top-level orchestrator)
- `interface.clj` Layer 2.9 banner with re-exports
- `policy_eval_responder_test.clj` (new) — 15 tests / 51 assertions covering: classify decisions across all 5 inputs,
  partition correctness, single-line apply happy path + missing-file + out-of-range rejection, materialize comment-id
  decoration on success and failure, end-to-end with mocked git/gh (file mutations verified, reply + resolve called per
  fix), escalation path keeps file unchanged, no-applies path skips git operations entirely

### `bases/cli`

- `commands/pr_policy_respond.clj` (new) — CLI entry: parse URL, gh pr checkout, fetch comments via existing
  `pr-poller`, delegate to `respond-to-policy-comments!`, print per-class summary
- `main.clj` — registers `pr policy-respond` with `:args->opts [:url]`
- `messages/en-US.edn` — nine new `:pr/policy-respond-*` keys

## Operator surface

```bash
bb miniforge pr policy-respond https://github.com/<org>/<repo>/pull/<n>
```

Output:

```text
pr policy-respond: gh pr checkout #42
pr policy-respond: on branch chris/feat-foo
pr policy-respond: PR #42 — applied=3 failed=0 escalated=1 skipped=0 commit=abc1234deadbeef
pr policy-respond: comment 99887766 escalated — :policy-eval/multi-line-not-supported
```

## Reuse anchors

| Surface | Reuses |
|---|---|
| Payload extraction | `compliance-scanner.interface/extract-comment-payload` from #818 (round-trip inverse of the renderer) |
| Reply + resolve | `pr-lifecycle.github/reply-to-comment` + `get-thread-id` + `resolve-conversation` (existing) |
| Comment fetching | `pr-lifecycle.pr-poller/fetch-pr-comments` (existing) |
| URL parsing + checkout | Existing `parse-pr-url` + `gh pr checkout` |
| DAG result conventions | Standard `dag/ok` / `dag/err` from dag-executor |

No new runtime surface area outside the new responder + CLI command.

## Test plan

- [x] `clj-kondo`: clean.
- [x] `policy-eval-responder-test`: 15 tests / 51 assertions pass.
- [x] Full namespace tree loads under `:dev:test`.
- [ ] `bb pre-commit`: pending (will run on commit).
- [ ] Live smoke test against a real PR with a posted policy-eval review (manual, post-merge).

## What's NOT in this PR (deferred to v1)

- **Multi-line `:violation/suggested-fix`** — needs span computation from `:current`.
- **LLM dispatch for `:auto-fixable? false` comments** — current behavior is escalate; v1 can route to a fix sub-task
  with the rule-id + file as primer.
- **Auto-trigger** — operator runs `pr policy-respond` manually today; pairing with `pr review-monitor` to fire
  automatically post-review is a small follow-up.
- **Wiring into the existing `responder.clj` LLM path** — they coexist as separate commands today; v1 could route
  policy-eval comments out of the LLM path automatically.

## References

- Spec: N13 §2.3 (Comment payload), §2.5 (Comment Response Agent).
- #808 — N13 foundations (renderer + listener-registry spec).
- #818 — `pr review --post` (the path that produces these comments).
- #837 — `pr review-monitor` (auto-trigger for the review).
- #848 — Resume Signal Dispatcher.
