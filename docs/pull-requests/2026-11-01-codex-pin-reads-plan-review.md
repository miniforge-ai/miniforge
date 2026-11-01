<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(agent,phase): plan and review record whether the pinned landings were read

**Tier:** Codex §7.4.2 / §7.4.3 coverage
**Theme:** recorded flows, consultation provenance

## Problem

The consultation summary's `:pin-read?` answers one question: did the
agent actually fetch its pinned landings with `context_read`
(Codex SPEC §7.4.3)? It is computed from the session's recorded
`context-reads.edn` (§7.4.2). Only the implementer supplied that log.
Plan and review both called `consultation-summary` with `nil`, so for
those phases `:pin-read?` was always nil (unknown). The question went
unanswered in two of the three consulting phases.

The causes were different:

- **Review.** The reviewer runs in `with-readonly-session`. That function
  read only `answers.edn` before cleanup deleted the session directory,
  so `context-reads.edn` was destroyed unread, in both host and capsule
  mode.
- **Plan.** The planner runs in `with-session`, which already returns
  `:context-reads`. The planner never destructured it, and neither did
  its submission-retry session.

## Changes

### `agent/artifact_session/readers.clj`

- `read-capsule-codex-answers` is replaced by
  `read-capsule-readonly-outputs`. It reads `context-reads.edn` and
  `answers.edn` with one `cat` chain, so it is still one executor round
  trip. The two segments are split on the existing full-line
  `capsule-output-boundary` sentinel, and the answers segment keeps its
  presence marker, so `:absent`, `:recorded` and `:unreadable` are still
  distinguished. Both files are written by the MCP server, so neither
  segment can contain an agent-authored boundary line. The old function's
  only caller was `with-readonly-session`.

### `agent/artifact_session.clj`

- `with-readonly-session` returns `:context-reads` alongside
  `:codex-answers` and `:codex-answer-log`. The capsule branch uses the
  new reader. The host branch composes `read-codex-answers` with
  `read-context-reads`. The alias moves to the new name.

### `agent/reviewer.clj`

- `invoke-reviewer-sessions` concatenates `:context-reads` across split
  sessions, since a pin read in any session was read. It is nil when no
  session surfaced a log, because unknown is not the same as "read
  nothing". The review result carries `:context-reads` next to
  `:codex-answers`.

### `agent/planner.clj`

- The primary session's and the submission-retry session's
  `:context-reads` are concatenated, primary first, onto the planner
  result. The key is absent when neither session surfaced a log.

### `phase-software-factory/plan.clj`, `review.clj`

- `consultation-summary` now receives the result's `:context-reads`
  instead of `nil`. The comments that said the session "does not surface
  :context-reads yet" are updated.

## Behavior

`:pin-read?` is now true or false for plan and review whenever the
session wrote a reads log. It stays nil (unknown) when no log was written.
For a pinned plan whose pin was never fetched, the `:codex-consultation`
gate now raises its existing `:codex-pin-not-fetched` warning. That
warning is informational and does not change the decision. The reviewer's
consultation is delivered as a prompt section, not as a pin, so its
summary is not `:pinned?` and the gate does not warn for review.

## Testing

Run here on Clojure 1.12 from Maven Central. Clojars returns 403 through
this environment's proxy, so the agent test namespaces, which pull in
malli, could not load. The new reader was exercised directly instead.
`readers.clj` loaded for real, with only `file-artifacts` stubbed: that
namespace drags in malli, so the stub carries its real `shell-quote`
copied verbatim.

- Fake executor, five cases, all correct: reads and answers; neither
  file; empty stdout; reads without answers; present-but-empty answers
  (`:unreadable`).
- Real bash round trip: the generated command ran against a temp session
  directory with real files, covering reads and answers, reads only, and
  an empty answers file. All parsed correctly.
- Every edited file reads cleanly.

New and updated tests, which CI runs:

- `artifact_session_test.clj`: `read-capsule-readonly-outputs-test`
  replaces `read-capsule-codex-answers-test`, covering one round trip, all
  answer-log states, and reads without answers.
  `with-readonly-session-test` adds two cases: reads survive cleanup, and
  no file gives nil.
- `reviewer_test.clj`: split sessions concatenate reads; one session
  without a log; no logs gives nil.
- `planner_test.clj`: retry reads append after the primary's; no logs
  gives no key.
