<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# feat: Ordered acknowledged publication engine

## Overview and motivation

Compose the merged commit journal and durable adapter into one internal
publication engine. Commit before recording or delivering; retries keep their
original acknowledgment and ordered delivery survives callback reentry.
This is the application layer between #1983 and public publisher wiring.

## Changes

- Resolve N3 family scope independently of incidental cross-reference keys.
- Construct explicit volatile or durable publication ports through one factory.
- Serialize commit and admission, then drain callbacks outside the commit lock.
- Keep acknowledged records queued on critical delivery failure; preserve fatal
  causes and interruption flags. Clear the delivery fault after successful retry.
- Preserve the existing public publisher in this PR. No default durability claim.

## Validation

Focused engine, scope, and critical-boundary JVM/BB checks and affected consumers
are required, followed by CLI build and packaged verification. The dependent
live-wiring source overlay already passes the unchanged OPSV recovery regression
(2 tests / 22 assertions) and durable public API checks (5 / 23). These integration
probes do not claim this engine-only PR changes production publication.

The fuller probe with corrected supervisory producers exposed cross-scope
contamination in OPSV evidence collection. Its workflow-ID query also selects
supervisory snapshots carrying that cross-reference. A scope-filtered diagnostic
passes, but the public scope query and consumer migration are not implemented.
Do not treat the initial source-overlay green result as end-to-end acceptance.

## Deployment and remaining work

Merge after adversarial review, clean current-head Copilot review, and all CI.
Then finish producer scope migration and enable the public publisher. Its current
combined diff exceeds the 600-line budget, requiring this genuine layer split.
Default durable ownership, retention/replay and sealed ranges remain unfinished.
The earlier acknowledgment plan document records the larger acceptance matrix.
