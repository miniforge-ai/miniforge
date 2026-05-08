<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: N13 closed-loop PR pipeline foundations

## Overview

Lay down the foundation pieces for the Closed-Loop PR Pipeline (N13):
PR-scoped policy review using the existing `compliance-scanner`,
violation-comment renderer keyed for the Comment Response Agent's
bot-comment table, the listener-registry data model for the Resume
Signal Dispatcher, the reviewer-planner prompt that primes the MCP
context cache, and the seam in `pr-monitoring-workflow.md` that
references all of the above.

This PR is **paper + scaffolding only**. No webhook subscriber. No
janitor dispatcher. No comment poster. The wiring this PR adds is
sufficient to run the Standards Reviewer step end-to-end against a
local checkout via `bb miniforge pr review`.

## Motivation

Operator-behavior mining over recent Claude + Codex sessions surfaces
PR-lifecycle drudgery as the dominant typed operator turn:

- ~448 manual `merged` confirmations
- ~80 `respond-to-comments` directives
- ~77 `resolve-conflicts` directives
- ~130 plan-approval confirmations
- ~30 idle-state probes

The N13 spec (drafted in the companion `Spec Additions/` worktree)
defines the closed loop that erases most of this. This PR lands the
smallest viable foundation against the existing components — every new
file references existing infrastructure rather than introducing new
runtime surface.

## Changes In Detail

### Spec — `specs/informative/`

- `pr-monitoring-workflow.md`
  - Bot-comment table extended with `miniforge-policy-evaluator[bot]`
    row pointing at N13 §2.5.
  - New section: **Component: Plan-Driven Context Cache (per N13 §2.4)**
    documenting the `reviewer-planner → context-cache → janitor` flow,
    the elision threshold, and the SHA-keyed cache reuse rule.
  - New section: **Component: Resume Signal Dispatcher (per N13 §2.7)**
    summarizing the listener-registry handoff and the three resume
    channels, with a forward reference to the listener-registry doc.
- `n13-listener-registry.md` (new)
  - Canonical schema for listener entries (PR URL → agents waiting on
    merge), the four state transitions, the three registration moments
    (authoring-agent emit, operator binding, workflow declaration),
    storage at `.miniforge/listener-registry.edn`, dispatch contracts
    per channel kind (`:pty`, `:miniforge-ipc`, `:webhook`), evidence
    artifacts, and N11 operator surface requirements.

### Agent prompt — `components/agent/resources/prompts/`

- `reviewer-planner.edn` (new)
  - Variant of `planner.edn` tuned for cache-curation in PR review
    context.
  - Output is a single-task Plan whose `:task/exclusive-files` becomes
    the file set loaded into the MCP context cache for the downstream
    Standards Reviewer (and any janitor that follows).
  - Tighter turn budget (30 vs 80) and progress monitor (90s/240s vs
    180s/600s) — the input is bounded by diff size, not full-spec
    embedding.
  - File-set selection rules cover diff-touched files, one-hop callers
    of changed public APIs, standards-pack rule globs that overlap the
    diff, sibling tests, and pack manifests.

### Code — `components/compliance-scanner/`

- `src/.../comments.clj` (new)
  - Pure rendering layer for classified Violations → PR comment
    records per N13 §2.3.
  - `violation->payload` builds the inner `:violation/...` map.
  - `violation->comment` builds the full comment record with the
    embedded EDN payload block.
  - `violations->comments` is the bulk renderer with stable sort
    (path, line, rule-id).
  - `extract-payload` is the round-trip helper used by the Comment
    Response Agent's bot-comment-table parser.
  - Severity inference: auto-fixable + non-critical → `:warning`,
    non-fixable + security/safety/critical category → `:error`,
    `:severity-override` wins.
- `src/.../interface.clj` (modified)
  - New Layer 1 entry point `pr-review` runs scan + classify + comment
    rendering as one composed call. Required `:base-ref`. Optional
    `:pack`, `:pack-info`, `:rules`. Read-only — does not call
    `execute!`.
  - Public re-exports: `violation->comment`, `violations->comments`,
    `extract-comment-payload`.
- `test/.../comments_test.clj` (new)
  - 9 tests / 29 assertions covering payload shape, severity
    inference, comment record shape, embedded-EDN block format,
    round-trip via `extract-payload`, bulk renderer stable order,
    auto-fixable flag passthrough, and pack-info injection.

### CLI — `bases/cli/`

- `src/.../commands/pr_review.clj` (new)
  - `run-pr-review!` — programmatic entry point operating on an
    existing repo checkout.
  - Output formatters: `:table` (default), `:edn`, `:json`. JSON uses
    a small inline serializer to avoid pulling cheshire into the
    surface.
  - `run-pr-review-by-path-cmd` — CLI entry for the `--repo + --base`
    flow used by janitors operating on existing worktrees.
- `src/.../commands/pr.clj` (modified)
  - `pr-review-cmd` was a TODO stub — now delegates to
    `pr-review/run-pr-review!`. Two flows:
    1. `bb miniforge pr review <pr-url>` — checks out the PR via
       `gh pr checkout`, derives base ref via `gh pr view --json
       baseRefName`, then invokes the impl.
    2. `bb miniforge pr review --repo <path> --base <ref>` —
       no-checkout path for janitors and dogfood.
- `src/.../main.clj` (modified)
  - `pr review` flag spec extended: `--url --repo --base --standards
    --pack --rules --out` with sensible defaults.

## Reuse anchors

Every new piece references existing infrastructure rather than
inventing new runtime surface:

| New surface                   | Reuses                                                          |
| ----------------------------- | --------------------------------------------------------------- |
| `pr-review` entry point       | `compliance-scanner.scan` `:since`, `classify`                  |
| Reviewer-planner prompt       | Existing `planner.edn` template + planner runtime               |
| Plan-driven cache for janitors| `bases/mcp-context-server` `context-cache.clj` `load-cache!`    |
| `pr review <url>` CLI         | Existing `parse-pr-url`, `checkout-pr!`, `gh pr view --json`    |
| Comment payload schema        | N9 read-only policy evaluation contract                         |

## Verification

- **Lint**: `clj-kondo` on all touched files — clean (0 errors, 0
  warnings).
- **Test**: `clojure -M:test` on the new
  `comments_test` namespace — 9 tests / 29 assertions pass.
- **Load**: `clojure -A:dev -e "(require 'ai.miniforge.cli.main)"` —
  full CLI namespace tree loads with the new require chain.

Manual smoke test path (not yet exercised in CI):

```bash
# Existing checkout, scan against base
bb miniforge pr review --repo . --base origin/main --out edn

# PR URL flow
bb miniforge pr review https://github.com/<org>/<repo>/pull/<n>
```

## What's NOT in this PR

These pieces are deliberately deferred — they belong in follow-up PRs
once the foundation lands:

- Webhook subscriber for `pull_request*` / `check_suite.completed`.
- Janitor dispatcher that fans out to Conflict Resolution / Comment
  Response / CI Failure Handler (those agents already exist as
  designs in `pr-monitoring-workflow.md`).
- Comment poster — `connector-github` integration that takes the
  rendered comment vector and posts via the GitHub Reviews API. The
  rendering side is complete; the posting side stays a TODO until the
  webhook trigger lands.
- Listener registry implementation (the schema is defined; the
  read/write/dispatch code lives in a follow-up).
- Closed-loop pipeline runtime composition (steps wired into a single
  `:closed-loop-pr` workflow type per N13 §3).

## References

- N13 spec — drafted in companion `Spec Additions/` worktree, branch
  `claude/angry-goodall-3e65e9`, commit `1187ef9`.
- `specs/informative/pr-monitoring-workflow.md` (extended in this PR).
- N9 — External PR Read-Only Policy Evaluation
  (`work/n09-external-pr-read-only-eval.spec.edn`).
