<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence validation

## Scope

Expose manager-free validation of portable N6 bundle structure, domain values,
optional OPSV evidence, and declared content hashes. Unhashed base bundles remain
valid inputs before finalization. Content integrity does not establish authority.

Keep the existing manager protocol compatible and document its limited legacy
validation contract. Published-evidence consumers must use the new canonical API.
OPSV finalization validates the candidate with that API before sealing or changing
assembly state. Hash verification excludes both hash and signature per N6.

## Standards adversarial pass

Reuse canonical schema maps, OPSV Malli schema, bounded artifact input checks,
and the established N6 content hash. Separate pure validation from its named
exception boundary. Return structured diagnostics and construct reports once.
No storage manager, network operation, or authority is created by validation.
Split the existing nested finalizer into reference ordering/checks, candidate
validation, atomic publication, and retry orchestration. Assembly and finalization
share one localized failure constructor. Each namespace has at most three strata.

## Verification

Regressions cover required fields, invalid scalar and nested domain values,
content tampering, unsupported objects, and deferred unbounded sequences.
Required nullable fields distinguish explicit nil from absence; optional fields
still validate their values when present.
Nested constraints and violations reuse their canonical domain schemas.
Concurrent accumulation invalidates a prepared candidate instead of losing the
new reference or sealing incomplete evidence. All three evidence consumers and
the packaged canonical/assembly regressions pass. Polylith, kondo, strata and the
component-wide standards scan pass; final-head review and CI remain merge gates.
