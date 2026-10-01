<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Lossless OPSV evidence checkpoints

## Scope

Preserve assembly data across the shared workflow's text-oriented checkpoints using the bounded artifact snapshot codec.
Bind snapshots to their workflow, bundle identity, and checkpoint material kind.
Reject corrupt snapshots without falling back to display-oriented maps.
Snapshot encoding failures prevent phase success.
Provide a bound actuation snapshot that retains the sanitized preflight evidence base
alongside adapter output. Recovery uses that captured base even when runtime options
are missing or changed. The finalization PR #1952 wires capture and recovery into the lifecycle.
This PR is stacked on canonical evidence validation (#1957); retarget main after
that prerequisite merges. The audit-retry prerequisite (#1955) is already on main.

## Standards adversarial pass

Separate snapshot conversion, assembly orchestration, and phase-result construction.
Reuse public artifact and evidence interfaces and canonical result constructors.
Keep namespaces within three strata and diagnostics in the existing message catalog.
This extracts the independently testable checkpoint portion of finalization; it performs no provider actions.

## Verification

Exercise corruption, wrong-workflow/bundle/material-kind restoration, successful restoration, and encoding failures.
Actuation snapshot tests cover changed or absent runtime evidence bases and reject output-only legacy snapshots.
Run both phase consumers, the packaged checkpoint tests, and the component standards scan before merge.
The project integration test runs a completed phase through the real workflow checkpoint writer/reader.
It discards runtime storage and the display assembly, then restores the exact snapshot with set, list, and timestamp types.
CI runs it explicitly.
