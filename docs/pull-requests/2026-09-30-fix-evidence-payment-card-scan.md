<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: shared payment-card detection

## Overview and motivation

N6 section 7.2 requires payment-card detection before sealing. The evidence scanner
and shared redaction component currently miss card numbers. This foundation fixes
that gap for the finalizer in #1957 and existing redaction consumers.

## Changes and standards

Use one checksum-aware detector for contiguous, space-separated, and hyphenated
card numbers. Detection and replacement share the same predicate. Preserve
surrounding audit text and reject invalid checksums rather than blanket-redacting
every long number. Token boundaries exclude UUID/hash fragments and partial matches
inside overlong digit groups. The public redaction interface remains the cross-component boundary.

## Testing plan

Test known synthetic card numbers, invalid checksums, nesting, scanner labels,
and redaction idempotency. Run redaction and evidence consumers serially, packaged
Babashka tests, standards scans, normal hooks, review, and CI.
All four redaction consumers and all three evidence consumers passed. The built
CLI passed 49 tests / 626 assertions, including the collector regression that
initially exposed UUID false positives. Both component standards scans are clean.

## Deployment and related work

Merge independently to main, then integrate into #1957. No external effects or
stored-data migration. Historical sealed values are not modified.

## Checklist

- [x] Shared detector and scanner regressions pass
- [ ] Standards, review, and CI settle
