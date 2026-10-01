<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Portable gate-resolution evidence contract

## Scope

Clarify the wire representation of resolved rules already required by N6.GE.1
and N4 section 5.5. The previous N6 example omitted that required field.
This documentation prerequisite keeps implementation PR #1972 bounded.

## Contract

Retain rule ownership, effective settings, selection, and post-overlay proposals
in a separate resolution trace. The resolved set references only enabled rule IDs.
Use canonical keyword pack IDs throughout. Refer to exact hashed pack artifacts
for remaining rule definitions. Preserve disabled and filtered trace entries for auditing.
Record evaluation identity and the captured gate override setting for waiver joins.
Ordinary waivers remain restricted to retained medium-or-less violations.
Stable N6.GE.6–N6.GE.10 requirements and matching conformance cases cover these joins.

## Standards and verification

Fix every prose-lint finding in the touched spec without changing unrelated
requirements. Preserve the historical annex's date and scope; it does not claim
current implementation completeness. Review against N4 resolution and waiver rules.
Run prose lint and normal hooks, then require current-head review and all CI.
No runtime code changes or production gate-capture claims are included.
