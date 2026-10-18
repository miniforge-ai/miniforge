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
- Refuse unregistered supervisory family members, including the existing spec
  snapshot extension identified by N3 Annex A.3. Their entity contracts need an
  explicit specification amendment before acknowledged publication can accept them.
- Construct explicit volatile or durable publication ports through one factory.
- Serialize commit and admission, then drain callbacks outside the commit lock.
- Keep acknowledged records queued on critical delivery failure; preserve fatal
  causes and interruption flags. Clear the delivery fault after successful retry.
- Preserve the existing public publisher in this PR. No default durability claim.
- Migrate existing supervisory emitters to stamp canonical entity scope keys
  through one shared constructor, preserve envelope failures, and localize their
  summaries. Regenerate the nine affected golden contract fixtures.

## Validation

Focused engine, scope, and critical-boundary JVM/BB checks and affected consumers
are required, followed by CLI build and packaged verification. The dependent
live-wiring source overlay already passes the unchanged OPSV recovery regression
(2 tests / 22 assertions) and durable public API checks (5 / 23). These integration
probes do not claim this engine-only PR changes production publication.

The fuller probe with corrected supervisory producers exposed cross-scope
contamination in OPSV evidence collection. Its workflow-ID query also selects
supervisory snapshots carrying that cross-reference. A scope-filtered diagnostic
passed, identifying the query defect. The dependent implementation now adds an
authoritative scope query and migrates evidence collection. Unchanged OPSV recovery
and query regressions pass 4 tests / 31 assertions. Supervisory events are active,
without diagnostic overrides. This is still a source-overlay
result, not verification of a rebuilt or merged integration artifact.

## Deployment and remaining work

Merge after adversarial review, clean current-head Copilot review, and all CI.
Then finish producer scope migration and enable the public publisher. Its current
combined diff exceeds the 600-line budget, requiring this genuine layer split.
Default durable ownership, retention/replay and sealed ranges remain unfinished.
The earlier acknowledgment plan document records the larger acceptance matrix.
