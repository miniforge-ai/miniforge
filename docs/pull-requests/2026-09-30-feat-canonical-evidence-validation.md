<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence validation

## Scope

Expose manager-free validation of portable N6 bundle structure, domain values,
optional OPSV evidence, and declared content hashes. Unsigned base bundles remain
valid inputs before finalization. Content integrity does not establish authority.

Keep the existing manager protocol compatible and document its limited legacy
validation contract. Published-evidence consumers must use the new canonical API.

## Standards adversarial pass

Reuse canonical schema maps, OPSV Malli schema, bounded artifact input checks,
and the established N6 content hash. Separate pure validation from its named
exception boundary. Return structured diagnostics and construct reports once.
No storage manager, network operation, or authority is created by validation.

## Verification

Regressions cover required fields, invalid scalar and nested domain values,
content tampering, unsupported objects, and deferred unbounded sequences.
Run evidence consumers, packaged regressions, and scoped standards checks before merge.
