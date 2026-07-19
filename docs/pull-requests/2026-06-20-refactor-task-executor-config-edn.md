<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor(task-executor): externalize runtime/cost config to EDN

## Overview

Moves four operator-tunable runtime and cost literals in the `task-executor`
component out of code and into a single EDN data map, following the
config-as-data pattern (Dewey 007) already used by the `reliability`
component.

## Motivation

The `task-executor` component carried magic numbers at their use sites:

- the 60-second worktree-acquire timeout in `runner.clj`,
- the token pricing and input/output split in `calculate-cost-usd` in
  `runner.clj`,
- the `max-parallel` default of 4, appearing at two sites in
  `orchestrator.clj`,
- the 1000 ms scheduler poll interval in `orchestrator.clj`.

These are values an operator may want to tune (cost model, concurrency,
timeouts, poll cadence). Holding them as bare literals spreads the same
default across files and hides them from anyone scanning configuration.

## Changes

- New file
  `components/task-executor/resources/config/task-executor/defaults.edn`
  holding one non-namespaced data map with the Apache-2.0 header block. It
  carries `:worktree-acquire-timeout-ms`, a `:token-pricing` map
  (`:input-cost-per-million`, `:output-cost-per-million`,
  `:input-token-share`, `:output-token-share`), `:max-parallel`, and
  `:scheduler-poll-interval-ms`.
- `runner.clj`: loads the EDN via `io/resource` + `slurp` +
  `edn/read-string` into a private `defaults` def. The worktree-acquire
  timeout and the cost-function pricing/split now read from that map. The
  cost arithmetic is unchanged.
- `orchestrator.clj`: loads the same EDN into a private `defaults` def. Both
  `max-parallel` default sites and the scheduler poll interval now read from
  it.

The `:input-token-share` (0.7) and `:output-token-share` (0.3) are stored as
separate literals rather than deriving one from the other, so the cost
arithmetic produces bit-identical results to the prior code (deriving
`1 - 0.7` yields `0.30000000000000004` and would shift the output).

`components/task-executor/deps.edn` already had `"resources"` on `:paths`; no
change there.

## Verification

- Cost equivalence: `calculate-cost-usd` produces the same output before and
  after for sampled inputs — 1,000,000 tokens → 6.6, 2,000 → 0.0132, 0 → 0.0,
  12,345 → 0.081477. Confirmed against the prior literal-based arithmetic.
- Component tests (isolated, test-runner with `spec-parser` local root
  supplied): 36 tests, 116 assertions, 0 failures, 0 errors.
- Namespace load smoke for `runner` and `orchestrator`: both load and resolve
  the EDN resource.
- `bb poly:check`: OK.
