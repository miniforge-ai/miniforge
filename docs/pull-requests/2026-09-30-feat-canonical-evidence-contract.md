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
Shared semantic rules from #1965 enforce balanced migration creates/destroys in
both the producer and canonical validator; failed reports must identify that rule.
The outcome reliability prerequisite supplies canonical failure/degradation domains
and SLI measurement shapes; invalid optional values remain invalid after rehashing.
The optional-projection prerequisite normalizes release PR keys and omits
unavailable runtime metadata and error phases. Shared assembled-producer fixtures
pass this canonical API without weakening presence-aware validation.
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

After optional-projection integration, all three evidence consumers pass.
The rebuilt CLI passes 30 tests and 457 assertions across canonical validation,
optional projections, and reliability outcomes. Explicit stratum lint and kondo
pass for the conflict resolution and new round-trip regression.
