<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Track phase artifact publication receipts

## Purpose and layer

N3 phase completion names the artifacts produced by that phase. OPSV's existing
artifact-reference vector and kind-to-ID map accumulate across phases; projecting
all of them would incorrectly attribute earlier artifacts to the current phase.

This phase-opsv application slice records a separate per-publication-attempt vector
of artifact IDs, populated only after artifact storage and evidence assembly both
acknowledge each artifact. Begin each attempted publication with an empty vector;
never treat caller hints or prior-attempt receipts as current acknowledgments.

## Scope and dependencies

Based on main. Preserve cumulative reference behavior and durable recovery data.
Retain partial receipts on publication failure. No event payload or default-host
change is included; lifecycle outcome projection will consume these facts next.
Do not infer receipts for paths that never attempted artifact publication.

## Verification and deployment

Focused artifact tests passed: 17 tests, 111 assertions. All three product
phase-opsv suites passed serially (48 seconds). After updating to merged main,
the rebuilt CLI passed 23 tests and 166 assertions outside the source tree.
These cover receipt, storage failure, identity, event, and native-agent contracts.

The adversarial pass traced acknowledgment ordering, partial failure, inherited
receipts, and retry behavior; shared factories replace repeated discovery maps.
Changed Clojure files have zero lint findings and unchanged inferred strata.
Normal signed hooks, size budgets, exact-head review, and CI remain required.
Normal CLI deployment; no storage migration.
