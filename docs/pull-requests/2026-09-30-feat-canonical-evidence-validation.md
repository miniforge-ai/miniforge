<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence finalization

## Scope

Stacked on canonical contract #1959; retarget main after it merges.
Consumer presentation/export enforcement is reviewed independently in #1960.
Use the manager-free canonical validation API from #1959 for OPSV finalization.
That prerequisite validates portable N6 structure, domain values and declared hashes.
Unhashed base bundles remain valid assembly inputs. Content integrity does not establish authority.

Published-evidence consumers must use the canonical API through #1960.
OPSV finalization validates the candidate with that API before sealing or changing
assembly state. Hash verification excludes both hash and signature per N6.
Reject sealed base inputs instead of repairing or resealing them.
Stamp compliance and sealing timestamps before calculating the content hash.
Revalidate the actual scanned/redacted seal before CAS, including required
event-scope links and outcome tier. Deferred artifact availability is rejected
without realization. Scan failure cannot finalize the assembly.
Shared card detection/redaction comes from merged #1963. Metadata-only changes
also set redacted handling, and the seal regressions cover string/integral cards
and metadata without relying on serialized scanning or ordinary map equality.
Read-only recovery validates retained seals and reference correlation.
Recovery can adopt an existing published seal without replacing its timestamps or digest.
Publication here is atomic in memory; this PR does not claim disk durability or
the full N3 retention and replay contract.

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
After integrating #1963 and the shared semantic rules, all three evidence consumers
pass and the rebuilt packaged canonical/assembly suite passes 35 tests with 326
assertions. The component standards scan reports zero findings across 62 files.
After governance trace integration, all three evidence consumers pass; the rebuilt
packaged canonical and assembly suite passes 38 tests with 389 assertions.
Retained-seal recovery also enforces shared redaction across payload and metadata
after canonical portability checks. It rejects root and nested sensitive metadata
without changing the retained assembly or repairing and resealing the input.
After phase-link and bounded-metadata integration, all three evidence consumers
pass; rebuilt canonical and assembly tests pass 40 tests with 441 assertions.
