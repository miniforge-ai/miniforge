<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Lossless OPSV evidence checkpoints

## Scope

Preserve assembly data across the shared workflow's text-oriented checkpoints using the bounded artifact snapshot codec.
Bind snapshots to their workflow, bundle identity, and checkpoint material kind.
Reject corrupt snapshots without falling back to display-oriented maps.
Snapshot encoding failures prevent phase success.

## Standards adversarial pass

Separate snapshot conversion, assembly orchestration, and phase-result construction.
Reuse public artifact and evidence interfaces and canonical result constructors.
Keep namespaces within three strata and diagnostics in the existing message catalog.
This extracts the independently testable checkpoint portion of finalization; it performs no provider actions.

## Verification

Exercise corruption, wrong-workflow restoration, successful restoration, and encoding failures.
Run both phase consumers, the packaged checkpoint tests, and the component standards scan before merge.
