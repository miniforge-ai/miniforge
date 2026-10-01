<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Validate evidence at presentation and export boundaries

## Scope

Stacked on the canonical contract prerequisite #1959; retarget main after it merges.
Require a complete verified seal before CLI list/show or CLI/manager export.
Report invalid, tampered, and unsealed bundles without presenting their ordinary evidence details.
Do not silently repair legacy evidence or overwrite an export destination on validation failure.
Distinguish existing unreadable files from absent sources; show/export refuse the
former explicitly rather than reporting them as missing.
Serialize the exact validated value instead of rereading a mutable source file.
Use canonical EDN and preserve nanosecond instant precision when reading it back.
Use the bounded public file reader from #1961; reject trailing forms and input
larger than 16 MiB before parsing. Published fixtures include event links and tier.
The CLI fallback supports EDN; reject unsupported formats instead of mislabeling raw EDN as JSON or HTML.
This implements the consumer-boundary finding from #1957.

## Standards adversarial pass

Share the published-evidence predicate through the public component interface.
Separate validation, diagnostics, and filesystem effects into named stages.
Keep exception handling at named boundaries; preserve interruption and fatal errors.
Reuse one test bundle constructor and remove anonymous display configuration callbacks.
Keep every namespace within three strata and every commit below its reportable budget.

## Verification

Packaged CLI and manager tests verify valid round trips and rejection before export writes.
Tampering and missing seals leave existing destinations unchanged.
Run evidence consumers, CLI regressions, normal hooks, and standards scans before merge.
Current-head review and all CI, including Build, remain mandatory merge gates.
