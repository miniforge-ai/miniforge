<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence finalization

## Scope

Base: main. Canonical contract #1959, complete-value scanner #1979, and manager
export enforcement #1977 are merged. Shared compliance policy #1981 is a
prerequisite. CLI presentation/export enforcement in #1960 must merge first.
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
Recovery combines fresh and recorded findings and rejects understated sensitivity,
PII flags, or protected treatment. Compliance metadata is assembled before shared
redaction, preventing recorded finding fields or metadata from reintroducing secrets.
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
new reference or sealing incomplete evidence.
Retained-seal recovery also enforces shared redaction across payload and metadata
after canonical portability checks. It rejects root and nested sensitive metadata
without changing the retained assembly or repairing and resealing the input.
Regressions cover rehashed understated treatment and identity-preserving rejection of restoration.
Missing assemblies use the same not-found diagnostic in accumulation, finalization
and recovery, before validating any retained seal.
After the prerequisite merges, refresh once and run all evidence consumers,
rebuild the CLI, and run packaged canonical, assembly, publication and compliance
regressions. Require zero standards violations, normal signed hooks, a clean
exact-head review and all CI including Build. Preserve this branch and worktree.
