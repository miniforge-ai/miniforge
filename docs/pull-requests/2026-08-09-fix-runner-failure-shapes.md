<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): normalize the sandbox-setup failure result to `:execution/*`

## Overview

Two related shape defects in the CLI workflow-runner execution path, both
flagged by Copilot on PR #1666 and deferred there because that PR was
move-only. This one changes behavior.

1. `execute-with-events`' sandbox-setup-failure branch returned
   `{:success? false :errors [...]}` — a vocabulary nothing downstream
   reads. It now returns `{:execution/status :failed :execution/errors
   [...]}`.
2. `lifecycle/failure-message` stringified the whole first error entry. It
   now reads that entry's `:message`, printing the entry only when it has
   none.

## Motivation

Every consumer of a workflow result speaks `:execution/*`:

- `phase/succeeded?` / `phase/failed?` scan `[:status :execution/status
  :step/status :chain/status]`.
- `display/print-result` → `format-compact-summary` destructures
  `{:execution/keys [status metrics errors]}`.
- `lifecycle/publish-completion-event` reads `:execution/errors` and
  derives the event message from the result.
- `pr-lifecycle`'s `fix-succeeded?` tests `(not= :failed (get result
  :execution/status))`.

Against the old sandbox map every one of those read a key that was not
there. Traced on the pre-change code:

| Consumer | Old behavior | New behavior |
|---|---|---|
| `phase/succeeded?` | `false` (status nil ≠ `:completed`) — right answer for the wrong reason | `false` |
| `phase/failed?` | `false` — the failure was not positively detectable | `true` |
| `publish-completion-event` | `workflow/failed` with reason `"Workflow ended with status: unknown"`, `:errors [{:type :unknown-failure …}]` | reason is the sandbox error's message; `:errors` is the real entry |
| `display/print-result` (`:pretty`) | failure banner, then **no error lines at all** — `:execution/errors` was nil | failure banner plus the sandbox error |
| `pr-lifecycle/fix-succeeded?` | **`true`** — nil status is not `:failed`, so a sandbox that never came up counted as a successful fix and the bot replied "Fixed in latest push" on the review comments | `false` |

The last row is the one with user-visible consequences, and it is the
`(:key m default)` idiom the repo has been bitten by before: a producer
changing (or never adopting) a shape silently flips a consumer's default.

`failure-message`'s `(str (first errors))` is the same class of defect one
level down. Error entries are canonically maps —
`workflow/execution.clj` builds `{:type :phase-error :phase … :message …
:data … :anomaly …}` — so the event message was the printed map rather
than the sentence inside it.

## Layer

`bases/cli` only, plus its tests. No component or contract changes.

## Changes in Detail

- `workflow_runner/execution.clj`: new private Layer 0
  `sandbox-failure-result`, building `{:execution/status :failed
  :execution/errors [{:type :sandbox-setup-failed :message …}]}`.
  `execute-with-events` calls it instead of inlining the old map.
- The message is now `(or (:message (:error sandbox-error)) (str (:error
  sandbox-error)))`. `:sandbox-error` holds a `dag/err` result, whose
  `:error` is `{:code … :message …}`, so the old `(str (:error …))`
  embedded a printed map inside the message string — the same defect as
  (2), in the producer. The `str` form is kept as the fallback, so any
  non-map error stringifies exactly as it does today.
- `workflow_runner/lifecycle.clj`: `failure-message` prefers `(:message
  first-error)`, falls back to `(str first-error)`, and keeps the
  status-only sentence when there are no errors. Keyword lookup on a
  non-map returns nil, so string entries take the fallback without a
  `map?` guard.

### Checked, not changed

- Every other `:success?` / `(:errors …)` reader in `bases/cli` was
  grepped. The hits are unrelated producers: `web/github.clj`'s
  `sh-success?` over shell results, `commands/scan.clj` and
  `commands/policy.clj` over pack-compile results, `setup.clj` and
  `commands/run.clj` over spec *validation* results. No reader of the
  runner's result map used either key.
- `execute-with-events` still does not `close-artifact-store` on the
  sandbox-failure branch, unlike the pipeline branch. Pre-existing, out of
  scope here, left alone deliberately.
- `display/compact-error-lines` prints each entry with `str`, so a map
  entry renders as a map. That is already how canonical pipeline errors
  render; not touched.

## Testing Plan

- New `bases/cli/test/.../workflow_runner/execution_test.clj` — 5 tests
  driving `execute-with-events` down the sandbox branch against a real
  event stream: canonical result keys, `phase/succeeded?`/`failed?`
  classification, the published `workflow/failed` event's
  `:workflow/failure-reason`, the `:pretty` summary containing the error,
  and the non-map fallback.
- `kanban_test`'s three `failure-message` tests deliberately re-pinned
  from the stringify behavior to exact-message equality, plus two new
  cases: a map entry with no `:message`, and a plain-string entry.
- Non-vacuous: stashing the two source edits and re-running gives 9
  assertion failures across the new tests, each naming the old behavior
  (`"{:type :gate-failed, :message \"lint failed\"}"`, `"Workflow ended
  with status: unknown"`, `:execution/errors` nil, `:success?` present).
- Green: `execution-test`, `kanban-test`, `manifest-wiring-test`,
  `runner-control-wiring-test`, `display-test`, `operator-wiring-test`
  (59 tests / 115 assertions) and `pr-lifecycle.responder-test` (12 / 51),
  the consumer whose `fix-succeeded?` verdict flips.
- Pre-commit suite (342 tests / 1291 assertions + GraalVM compatibility)
  green.

## Standards Audit

- Stratified design (001/210): `sandbox-failure-result` has no
  in-namespace dependencies and sits at stratum 0; the test namespace's
  strata were placed by `stratum-lint --fix`.
- Testing (400): the sandbox context is built by a factory that mirrors
  `sandbox/setup-sandbox-context`'s real output, via the producer's own
  `dag/err` constructor. The one fixture that is *not* the producer shape
  is the fallback test, commented to say so and why.
- Localization (007-adjacent): no new user-facing strings; messages come
  from data already in the error result.

## Deployment Plan

Ships with the CLI. No migration, no config.

## Related Issues/PRs

- PR #1666 — the move-only split that surfaced both warts.

## Checklist

- [x] Both consumers traced against the pre-change code, not assumed
- [x] Other readers of the old keys grepped
- [x] New tests shown to fail without the source change
- [x] Affected namespaces run green
