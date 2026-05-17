# Fix: Reviewer issue schema rejected explicit nils, flipping :approved to :rejected silently

## Overview

The 2026-05-16 event-log-tool-visibility dogfood (see
[project_dogfood_findings_2026_05_16](../../.claude/projects/-Users-chris-ws-miniforge-ai-miniforge/memory/project_dogfood_findings_2026_05_16.md))
walked plan → implement → verify (78m, all 35 tests passed) → reviewer-emits
`:review/decision :approved` on a real GROUP 1 build. The `:review-approved`
gate then failed and the workflow died. The gate was right; the artifact
that reached it had `:review/decision :rejected`, not `:approved`. A strict
`ReviewIssue` schema in the parser cascade flipped the verdict before the
gate ever saw it.

## Motivation

Same shape as the PR #867 `:passed?` gate bug — strict reading of an
upstream contract silently flipping a downstream verdict. The
`ReviewIssue` Malli schema declared `:line`, `:file`, and `:suggestion`
as `{:optional true} [:int {:min 1}]` / `[:string {:min 1}]`. In Malli,
`{:optional true}` means "the key may be ABSENT," NOT "the key may be
present-but-nil." The reviewer LLM routinely emits `:line nil` on
file-level concerns (e.g., "no test covers the case where..."). Each
such issue failed `m/validate`, `valid-review-issues?` cascaded to
`false`, `parse-review-response` returned `nil`,
`parse-failed?` flipped `llm-decision` to `:rejected`, and an
actually-`:approved` review came through as rejected. The gate then
correctly rejected `:rejected`.

The dogfood post-mortem made this visible because the run printed the
LLM's raw `:approved` output to the chat log before the parser's silent
rejection. Without that print, the bug would have read as
"the reviewer never approves anything."

## Changes in Detail

### Schema: wrap optional fields in `:maybe`

`components/agent/src/ai/miniforge/agent/reviewer.clj` — `ReviewIssue`
schema now reads:

```clojure
[:map
 [:severity [:enum :blocking :warning :nit]]
 [:file {:optional true} [:maybe [:string {:min 1}]]]
 [:line {:optional true} [:maybe [:int {:min 1}]]]
 [:description [:string {:min 1}]]
 [:suggestion {:optional true} [:maybe [:string {:min 1}]]]]
```

Plus a docstring that explains *why* — so the next person who tries to
tighten this back doesn't reintroduce the bug.

### Parser regression tests (dogfood-shape EDN)

Two new tests in `reviewer_test.clj` pinned to fixtures that reproduce
the dogfood's exact LLM output:

- `test-reviewer-accepts-issues-with-nil-line` — issue with `:line nil`
  parses and `:review/decision :approved` survives.
- `test-reviewer-accepts-issues-with-all-optional-fields-nil` — issue
  with `:file nil`, `:line nil`, `:suggestion nil` parses cleanly.

### Gate-side contract pin

New `components/gate/test/ai/miniforge/gate/policy_test.clj`. The
`check-review-approved` gate had zero direct test coverage despite being
the load-bearing check between the reviewer and release. Pin the contract
from this side too:

- `:approved` and `:conditionally-approved` → `:passed? true`
- `:changes-requested` and `:rejected` → `:passed? false`
- Legacy `[:metadata :approved]`, `[:artifact/metadata :approved]`,
  `[:review :approved]` fallback paths still honored
- Empty artifact → fails closed

A future "tighten the approval check" can't loosen this back into a
false positive without flipping a red test.

### Scope check — is the same shape hiding elsewhere?

The "shape" is: an LLM-output parser calling `m/validate` inline on
an LLM-emitted map, with optional fields declared `{:optional true} T`
instead of `{:optional true} [:maybe T]`. Surveyed every
`parse-*-response` in the agent component:

- `parse-review-response` — affected (fixed here).
- `parse-code-response` (implementer) — only checks `(map? parsed)`;
  schema validation happens later in `:validate-fn` during the repair
  cycle, where rejection routes to repair, not to a verdict-flip.
- `parse-release-response` (releaser) — same as above.
- `parse-plan-response` (planner) — only checks `(map? parsed)`.
- `parse-eval-response` (meta-evaluator) — JSON path, no Malli.

So the bug is contained to reviewer. The cross-component contract test
in `policy_test` guards the gate side regardless.

## Testing Plan

- [x] `clojure -A:test:dev -M -e "(require 'clojure.test
  '[ai.miniforge.agent.reviewer-test] '[ai.miniforge.gate.policy-test])
  (clojure.test/run-tests 'ai.miniforge.agent.reviewer-test
                          'ai.miniforge.gate.policy-test)"`
  → 35 tests, 111 assertions, 0 failures.
- [x] `bb lint:clj` — clean on touched files (one pre-existing
  `prompts/load-progress-monitor` warning, unrelated).
- [x] `bb pre-commit` — passed cleanly on both commits in this PR.
  PR #893's PR doc reported a `workflow.runner-extended-test` stall in
  the stable-derived sweep; the first commit on this branch
  (`test(workflow): stub dag executor in governed-mode runner tests`)
  bisected and resolved that hang, so `bb pre-commit` is now reliably
  green again.
- [ ] Manual: rerun the dogfood after merge; expect the GROUP 1 build
  that previously got an LLM-`:approved` to now also pass the gate and
  proceed to release.

## Deployment Plan

Merge normally. Backwards compatible: `[:maybe T]` accepts both nil
and a valid T value, so any existing valid review still parses.

## Related Issues/PRs

- Same shape as PR #867 (`:passed?` predicate gate bug).
- Dogfood checkpoint: `c1abda63-3072-4f57-9871-6c177384968e`.
- Spec: `work/event-log-tool-visibility.spec.edn`.
- Memory: `project_dogfood_findings_2026_05_16.md` — full root-cause trace.

## Bonus: pre-commit hang fix

The first commit on this branch
(`ea751fd9 test(workflow): stub dag executor in governed-mode runner
tests so they don't hit Docker`) resolves the
`workflow.runner-extended-test` hang documented in PR #893's PR doc.

Chased via `bb test:since-stable:bisect` + a 20s-per-deftest
watchdog. Three tests hang:
`run-pipeline-governed-mode-{has-host-worktree,sets-execution-mode,rejects-worktree-fallback}-test`.
All three call `run-pipeline ... {:execution-mode :governed}` →
`create-docker-executor`, which blocks indefinitely when the local
Docker daemon is busy. The tests are unit tests of run-pipeline mode
propagation, not Docker integration tests, so stubbing the
dag-executor surface in a `with-mock-capsule-executor` macro is the
right scope. Each test now completes in ~200ms instead of hanging
past 20s.

Follow-up: a separate spec should add a configurable acquisition
timeout to `acquire-environment!` so production callers also fail
fast when Docker stalls (currently captured as a TODO in the commit
trailer; will file as a work spec once the supervisor for this
codebase merges this PR).

## Checklist

- [x] ReviewIssue schema accepts explicit `nil` on optional fields.
- [x] Parser regression tests pinned to dogfood-shape EDN.
- [x] Cross-component contract test for `check-review-approved`.
- [x] Surveyed peer LLM parsers for the same shape; confirmed contained.
- [x] `bb pre-commit` green on both commits in the branch.
- [x] Resolved the `workflow.runner-extended-test` hang from PR #893's PR doc.
