<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence contract prerequisite

## Scope

Extract the pure canonical validation API from #1957 to keep each review bounded.
Validate portable root and nested domain records, optional OPSV evidence, and declared hashes.
Declared seals require sealing and compliance timestamps; hashing excludes the hash and signature.
Seals also require the canonical workflow tier and unique, correlated event-scope
links with valid sequence ranges and counts. Semantic declarations must match root intent;
resource counts, passed status, and violation IDs must agree with the shared intent rules.
Actual behavior uses shared inference; zero changes also permit code-only refactoring.
Shared producer contracts cover semantic rules, reliability, optional metadata,
and canonical controls without relaxing field presence. Policy envelopes are optional.
Base: `fix/evidence-control-records` (#1969); retarget main after that prerequisite merges.
Event counts must equal the
inclusive scoped sequence range; missing events cannot be certified as complete.
Unsealed assembly inputs remain valid here, but are not publishable evidence.
Legacy manager validation remains compatible and explicitly documents its limited contract.
Production finalization wiring remains in #1957; CLI presentation and export enforcement follow separately.

## Standards adversarial pass

Reuse domain schemas and shared portability checks rather than duplicating validators.
Keep schema composition pure and exception handling at one named boundary.
Separate field presence from nullability and test both positive and negative domain values.
No provider effects, authority grants, or storage mutations occur during validation.

## Verification

Run all three evidence consumers, normal commit hooks, and the component standards scan.
Regressions cover malformed nested records, incomplete seals, tampering, and nonportable inputs.
Require clean current-head review and CI before merging.

All three evidence consumers and dashboard tests pass after #1969 integration.
Rebuilt packaged canonical/dashboard regressions pass 16 tests and 272 assertions.
Optional phase status is validated when present. Canonical-keyed policy violations
normalize keyword IDs and legacy severities just like legacy-keyed producer records.
Legacy artifact maps retain their `:id` fallback; explicitly malformed canonical
IDs are not replaced. JVM and rebuilt packaged canonical/approval regressions
pass 16 tests and 288 assertions after the approval-alias integration.
The zero-count refactor exception requires declared refactor intent. All three
evidence consumers pass after readiness/N8 integration; rebuilt packaged canonical
and control schema regressions pass 16 tests and 296 assertions.
