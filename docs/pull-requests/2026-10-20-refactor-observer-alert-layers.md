<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: separate observer alert evaluation layers

## Motivation and scope

Prepare the observer for the approved heartbeat contract by separating alert
presentation and tool-call state from rule evaluation. Preserve existing legacy
semantics and API aliases; localize emitted default messages. The existing alert
namespace exceeds the three-stratum limit and mixes these responsibilities.

Current heartbeat interpretation follows separately. This PR neither registers
nor enables current-journal publication of `observer/alert-fired`.

## Verification plan

Retain existing alert and subscriber tests, add focused presentation and state
regressions, test all consuming projects serially and verify the packaged CLI.
Check actual inferred strata, Clojure lint and standards against the PR base.
Use normal commit/PR budgets, signed hooks, fresh review and all CI gates.
