<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Canonical evidence validation

## Scope

Expose manager-free validation of portable N6 bundle structure, domain values,
optional OPSV evidence, and declared content hashes. Unhashed base bundles remain
valid inputs before finalization. Content integrity does not establish authority.

Keep the existing manager protocol compatible and document its limited legacy validation contract.
This document describes the validator prerequisite now tracked in #1959.
Production finalization is implemented in #1957; presentation and export enforcement are implemented in #1960.
Those integration PRs remain separate review and merge gates.

## Standards adversarial pass

Reuse canonical schema maps, OPSV Malli schema, bounded artifact input checks,
and the established N6 content hash. Separate pure validation from its named
exception boundary. Return structured diagnostics and construct reports once.
No storage manager, network operation, or authority is created by validation.

## Verification

Regressions cover required fields, invalid scalar and nested domain values,
content tampering, unsupported objects, and deferred unbounded sequences.
Required nullable fields distinguish explicit nil from absence; optional fields
still validate their values when present.
Run evidence consumers, packaged regressions, and scoped standards checks before merge.
