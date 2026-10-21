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

## Results and adversarial review

All three consuming projects (miniforge, core and TUI) passed in the serial
observer run (50 seconds); data-foundry does not consume this brick. Fresh CLI:
39,211,735 bytes. Isolated packaged alert tests: 16 tests, 65 assertions. No
production-source overlay was used for the package check.

The stratum gate exposed a four-layer tool-state chain during development; the
pure bounded outcome transition was separated and the normal hooks rerun. Rule
schemas now share their field definitions, threshold predicates are pure, and
timeout state changes have a dedicated owner. Existing heartbeat-only evaluation,
strict threshold comparison, caller templates, one-shot timeouts and outcome
window semantics remain covered. No current alert event contract was introduced.
