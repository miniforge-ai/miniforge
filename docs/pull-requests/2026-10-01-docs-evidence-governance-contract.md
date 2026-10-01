<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Portable gate-resolution evidence contract

## Scope

Clarify the wire representation of resolved rules already required by N6.GE.1
and N4 section 5.5. The previous N6 example omitted that required field.
This documentation prerequisite keeps implementation PR #1972 bounded.

## Contract

Retain rule ownership, effective severity and enablement, selection, and each
contributor's post-overlay proposal. Refer to exact hashed pack artifacts for
the remaining rule definition. Preserve disabled and filtered rules for auditing.
Record evaluation identity and the captured gate override setting for waiver joins.
Ordinary waivers remain restricted to retained medium-or-less violations.

## Standards and verification

Fix every prose-lint finding in the touched spec without changing unrelated
requirements. Preserve the historical annex's date and scope; it does not claim
current implementation completeness. Review against N4 resolution and waiver rules.
Run prose lint and normal hooks, then require current-head review and all CI.
No runtime code changes or production gate-capture claims are included.
