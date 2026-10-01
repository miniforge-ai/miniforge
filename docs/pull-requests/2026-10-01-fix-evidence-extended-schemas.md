<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Defined optional N6 evidence sections

## Scope and dependencies

Domain schemas based on main. This is a prerequisite of canonical validation PR #1959.
It addresses the missing-section review finding. No deployment step is required.
DAG, task, merge, annotation and pack-run evidence remain optional;
when supplied, they must conform to N6 sections 2.7, 2.9 and 2.11.

## Design and standards review

Compose named nested Malli schemas rather than duplicate map predicates or
introduce procedural validation. Keep domain records separate by concern and
reuse metric and lifecycle schemas. The root schema uses presence-aware keys.
This validates declared evidence; it does not create runtime producers or prove
external PR state, execution authority, or durable event availability.

## Verification and merge gates

Test absent sections, valid nested records, wrong scalar and collection values,
missing required fields and malformed nested records. Run evidence consumers,
packaged regressions and normal hooks serially. Require standards review, all CI
checks and a settled current-head Copilot review before merging to main.
