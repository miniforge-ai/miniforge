<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: protect the runner's complete resource lifetime

## Scope and dependencies

Dependent application integration branch from fix/workflow-cleanup-sequence,
which follows workflow ownership #2080 and merged resource cleanup #2079.
Open only after those parents merge and the branch is aligned with main.

Protect execution and preparation after environment acquisition, attempt heartbeat
shutdown and post-workflow cleanup independently, and preserve the original
execution result or critical failure through cleanup. Keep the public runner API
and recursion port, with session execution separate from resource lifetime.
Ordinary observer cleanup failures retain warning behavior; critical and
interrupted causes must propagate after resource cleanup rather than be swallowed.

## Verification and deployment

Exercise the public runner with failing preparation, heartbeat shutdown and
cleanup ports, preserving primary failure identity and resource release. Retain
the existing workflow acceptance suites. Run consuming JVM projects serially,
rebuild and verify the isolated CLI, and apply adversarial standards review.
Keep normal budgets, signed hooks, fresh exact-head review and all CI gates.
No wire-contract change is intended; deployment uses the ordinary package.

The session explicitly captures the public pipeline-builder and output-extractor
ports. A full-suite regression exposed that bypassing the latter lost the terminal
test's evidence injection; preserving both ports restores that compatibility.
The corrected three-consumer JVM suites passed (1m37s). The fresh CLI is
39,228,892 bytes; isolated lifetime and terminal-admission tests passed 12 tests/
61 assertions without production source overlays. All task entry classpaths load.
The standards scan is clean, and lint has zero warnings/errors. New lifetime
tests measured 0.7–66 ms on the JVM, so they remain in the brick suite.

The implementation is split into normal-budget commits for lifetime/session
helpers and heartbeat boundary, then the public runner integration and regressions.

The final adversarial pass reused the parent's cleanup-attempt boundary for
heartbeat warnings rather than duplicating critical-cause policy. JVM lifetime,
heartbeat and terminal regressions still pass twelve tests/61 assertions.
