<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(agent,plan): the plan phase records whether the planner read its pin

**Tier:** Codex §7.4.2 / §7.4.3 coverage
**Theme:** recorded flows, consultation provenance

## Problem

The consultation summary's `:pin-read?` answers one question: did the agent
fetch its pinned landings with `context_read`? (Codex SPEC §7.4.3.) It is
computed from the session's `context-reads.edn` (§7.4.2).

The plan phase gives the planner a real pin file. `pin-outcome` puts the
landings at `codex-pin/pin-path` in the planner's existing files, exactly
as implement does. But `plan.clj` passed `nil` for the reads, so plan's
`:pin-read?` was always nil (unknown). The data was already there:
`with-session` returns `:context-reads`, and so does the
submission-retry session. The planner never destructured it.

## Changes

- `agent/planner.clj`: destructures `:context-reads` from the primary
  session and from the submission-retry session (`recover-submitted-plan`),
  and concatenates them onto the planner result, primary first. A pin read
  in either session counts as read. When neither session surfaced a log
  the key is absent, so `:pin-read?` stays nil (unknown), not false.
- `phase-software-factory/plan.clj`: passes `(:context-reads invoked)` to
  `consultation-summary` instead of `nil`, and updates the comment that
  said the planner "does not surface :context-reads yet".
- `phase-software-factory/codex_pin.clj`: `consultation-summary` no longer
  counts a pin lookup that resolved `:source :absent` as a read. The MCP
  server records failed lookups too, and this function checked only the
  path, so asking for the pin and getting nothing set `:pin-read? true`
  and suppressed `:codex-pin-not-fetched`. This was pre-existing on the
  implement path. It is fixed in the shared function, so implement and
  plan both get it. Found by Copilot on this PR.
- `phase-software-factory/review.clj`: comment only. Review keeps `nil`,
  but the old comment's reason ("the reviewer session surfaces no reads
  log") was wrong. The real reason is that the reviewer's landings are a
  prompt section (`landings-outcome`), not a pin file, so there is nothing
  to `context_read`. Passing reads there would make `:pin-read?` false on
  every review, and the consultation gate would then raise
  `:codex-pin-not-fetched` on every run. The comment now says so, so that
  the next person doesn't "fix" it.

## Behavior

For a configured codex, plan's `:pin-read?` is now true or false whenever
the planner's session wrote a reads log. When the pin was not fetched, the
`:codex-consultation` gate raises its existing `:codex-pin-not-fetched`
warning. That warning is informational and does not change the decision.

## Testing

- `planner_test.clj` gets two new cases. Retry reads append after the
  primary's. With no logs, the planner result has no `:context-reads` key.
- `codex_pin_test.clj` gets two new cases: an `:absent` pin lookup gives
  false, and each content-bearing source (`:cache`, `:filesystem`,
  `:refresh`) after an `:absent` lookup still gives true. The real
  `consultation-summary` form, evaluated on Clojure 1.12, passed all six
  pin-read cases: nil, other path, cache, absent, absent then refresh,
  and filesystem.
- Every edited file reads cleanly with the Clojure 1.12 reader.
- The planner test namespace needs malli from Clojars, which returns 403
  through this environment's proxy, so CI runs it.
