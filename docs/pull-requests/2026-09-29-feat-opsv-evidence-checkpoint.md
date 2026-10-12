<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Lossless OPSV evidence checkpoints

## Scope

Preserve assembly data across the shared workflow's text-oriented checkpoints using the bounded artifact snapshot codec.
Bind snapshots to their workflow, bundle identity, and checkpoint material kind.
Reject corrupt snapshots without falling back to display-oriented maps.
Snapshot encoding failures prevent phase success.
Replay reuses audit occurrences for every phase, including after snapshot failure.
Persist acknowledged event sequences with the assembly, so N6 scope links and
counts survive a fresh runtime stream. Capture the configured workflow tier
(N1 default: standard) in the preflight base used by recovery.
Current retries require a journal receipt, not an assembly reference. Legacy recovery requires a retained sequence
when the original event is unavailable; it never invents a position or duplicates that occurrence.
Individual audit publications accumulate only their receipt. Checkpoint persistence collects the full workflow range
using the stream's profile-specific query options. Collection failures prevent checkpoint success.
Recognize both current and legacy anomaly formats throughout phase completion. Sealed assemblies persist unchanged.
Initial tier capture ignores caller outcome data; only a validated bound recovery snapshot supplies a retained tier.
Provide a bound actuation snapshot that retains the sanitized preflight evidence base
alongside adapter output. Recovery uses that captured base even when runtime options
are missing or changed. The finalization PR #1952 wires capture and recovery into the lifecycle.
Base: main after merged canonical evidence validation (#1957) and current OPSV publication (#2034, #2035).
Integrated main once at `e9a9afd5d`; preserved existing checkpoint edits in a retained stash before reconciliation.

## Standards adversarial pass

Separate snapshot conversion, assembly orchestration, and phase-result construction.
Reuse public artifact and evidence interfaces and canonical result constructors.
Keep namespaces within three strata and diagnostics in the existing message catalog.
This extracts the independently testable checkpoint portion of finalization; it performs no provider actions.
Keep main's reviewed privacy handling, pure assembly transitions, receipt validation, and indexed current retries.
Reject duplicate interface definitions introduced by automatic merging. Retain the first confirmed position for each event.
Explicitly lint checkpoint-owned strata even though the normal merge hook exempts incoming files.

## Verification

Exercise corruption, wrong-workflow/bundle/material-kind restoration, successful restoration, and encoding failures.
Actuation snapshot tests cover changed or absent runtime evidence bases and reject output-only legacy snapshots.
Run both phase consumers, the packaged checkpoint tests, and the component standards scan before merge.
After integration, all three phase/evidence consumer projects passed serially (1m10s).
The real workflow disk test passed (1 test / 7 assertions).
Review regressions cover legacy collection errors, immutable sealed checkpoints, untrusted outcome tiers,
and preserving the captured tier when recovery workflow options change.
Both phase consumers passed after the corrections (45s). Rebuilt CLI: 39,157,255 bytes.
Isolated package checks passed 53 tests / 372 assertions, with no production source overlays.
Pinned standards scan, explicit owned-file strata, and Clojure warning/error checks passed.
The new current-journal test verifies contiguous scoped positions, foreign-workflow isolation, restored snapshots,
query-failure propagation, and idempotent retry after reopening. Cross-workflow global ordering is not assumed.
The project integration test runs a completed phase through the real workflow checkpoint writer/reader.
It discards runtime storage and the display assembly, then restores the exact snapshot with set, list, and timestamp types.
CI runs it explicitly.
