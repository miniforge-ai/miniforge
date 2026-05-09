<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(n13): batched review poster + `pr review --post`

## Overview

Make the N13 Standards Reviewer pipeline run end-to-end against a real
PR. The renderer landed with #808; this PR adds the **comment poster**
on top — `bb miniforge pr review <pr-url> --post` now executes the full
loop: scan → classify → render → post a single batched review on the PR.

This is the first half of the deferred N13 implementation work called
out in the #808 PR doc. The other half (webhook subscriber so it
auto-fires on `pull_request.opened`) lands in a follow-up.

## What's new

### `components/pr-lifecycle`

- `github.clj` `post-review!` (Layer 1.5) — bundles N inline comments
  into one PR review via the create-a-review REST endpoint
  (`POST /repos/{owner}/{repo}/pulls/{pull_number}/reviews`) with
  `event: "COMMENT"` (non-blocking — no approval / changes-requested
  semantics). Single API call instead of N top-level comments;
  reviewers see one expandable review on the PR.
- `github.clj` `run-gh-with-stdin` (Layer 0 helper) — `gh api ... --input -`
  invocation with stdin piping. Needed because `gh api -f`/`-F` can't
  cleanly encode the nested `comments[]` array.
- `github.clj` `review-comment->github-comment` (Layer 1.5 helper) —
  translates the renderer's `:comment/path` / `:comment/line` /
  `:comment/body` shape (or a flatter `{:path :line :body}`) to the
  `{path,line,side,body}` shape the GitHub API expects.
- `interface.clj` `post-review!` re-export with full docstring.
- `test/.../github_test.clj` — 6 tests / 22 assertions covering:
  endpoint + stdin wiring, payload shape from both renderer + flat
  shapes, success parsing into `{:review-id :url :state :comment-count}`,
  non-zero exit → typed `:gh-command-failed` error with stderr,
  `process/shell` exception → typed `:gh-exception`. Mocks via
  `with-redefs` on `babashka.process/shell`.

### `bases/cli`

- `pr_review.clj` — `run-pr-review!` learns `:post?` and `:pr-number`
  options. New `maybe-post-review!` private helper handles the post
  call: skips silently on zero violations, errors out if `--post` was
  set without a resolvable PR number, otherwise builds a markdown
  body from the review summary and dispatches to
  `pr-lifecycle/post-review!`.
- `pr.clj` — URL flow plumbs `--post` through and supplies the
  `:pr-number` parsed from the URL automatically.
- `main.clj` — `pr review` flag spec gains `:post {:coerce :boolean}`
  and `:pr-number {:coerce :long}`.
- `messages/en-US.edn` — four new keys under `:pr/review-`:
  `posted`, `post-failed`, `post-skipped-empty`, `post-pr-required`.

## Operator surface

```bash
# Render only (existing behavior — unchanged)
bb miniforge pr review https://github.com/<org>/<repo>/pull/<n>

# Render + post one batched review on the PR
bb miniforge pr review https://github.com/<org>/<repo>/pull/<n> --post

# By-path flow (operator already has the checkout)
bb miniforge pr review --repo . --base origin/main --post --pr-number 808
```

## Reuse anchors

| New surface         | Reuses                                                   |
| ------------------- | -------------------------------------------------------- |
| `post-review!`      | Existing `pr-lifecycle/github.clj` patterns (`process/shell`, `dag/ok`/`dag/err`, `cheshire`) |
| Renderer payload    | `compliance-scanner/comments` from #808 — unchanged      |
| URL flow            | Existing `parse-pr-url`, `checkout-pr!`, `gh-pr-base-ref` |
| Auth                | `gh` CLI's existing user token — no new auth surface     |

## What's NOT in this PR (deferred)

- Webhook subscriber for `pull_request.opened` / `synchronize` (so the
  pipeline auto-fires, no operator hand needed).
- Listener registry implementation (schema is in N13 §2.7; read/write/
  dispatch is a follow-up).
- Resume Signal Dispatcher.
- `:closed-loop-pr` workflow type composing all eight step workflows
  from N13 §3.

## Test plan

- [x] `clj-kondo` on touched files: clean.
- [x] `github_test`: 6 tests / 22 assertions pass under `:dev:test`.
- [x] Full namespace tree loads under `:dev:test`.
- [ ] `bb pre-commit`: pending (will run on commit).
- [ ] Live smoke test against an open miniforge PR with 1+ standards
      violations (manual, post-merge).

## References

- Spec: N13 §2.2 (Standards Reviewer step), §2.3 (Comment payload schema).
- Renderer: shipped in #808 (`components/compliance-scanner/.../comments.clj`).
- Companion to upcoming webhook PR.
