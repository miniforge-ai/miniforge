<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: exercise the full OPSV runner against the current journal

## Scope

Add a real shared-runner integration case to the already registered OPSV
finalization suite. Run all seven OPSV phases and done with an explicit
caller-declared intent and a current journal. Verify phase completions, terminal
evidence, workflow correlation, zero-based contiguous sequence numbers, and exact
history after closing and reopening the journal.

Extract shared run setup and evidence assertions rather than copying the existing
legacy and recovery tests. Keep the genuine inferred dependency depth at three
strata or fewer. The slow test remains in the project integration suite, already
selected by tasks/test_runner; no production behavior changes.

The initial diagnostic without declared intent correctly failed admission. The
test supplies that required fact; it does not weaken the current payload schema.
This proves runner compatibility, not completion of the public CLI host switch.

## Verification

Workflow brick tests pass across all three consuming projects (1m27s). Those
brick-only runs do not select this project integration suite. Four OPSV project
suites were therefore run explicitly and pass nine tests/167 assertions on the
JVM. The rebuilt CLI (39,230,939 bytes) passes the same nine tests/167 assertions
from an isolated working directory with test sources only, no production overlays.
An initial assertion incorrectly assumed one-based journal sequences; it was
corrected to the established zero-based sequence contract and both runs pass.

Normal budgets, standards scan, signed hooks, fresh review and all CI gates apply.
