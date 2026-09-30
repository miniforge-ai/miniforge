<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preserve lossless artifact checkpoint snapshots

## Overview

Expose validated, bounded artifact snapshots as strings, without publishing them.
The shared workflow checkpoint format normalizes timestamps to strings. Domain
evidence needs exact dates and nanosecond Instant values to retain its content hash.

## Design and dependencies

Reuse the immutable publication envelope, checksum, schema and exception boundary.
The public interface validates inputs; the snapshot namespace owns string/byte
conversion. No new component or library dependency is introduced. Encoding is not
publication, authorization or writer authentication. Callers must validate domain
ownership and must not derive authority from snapshot content.

The existing 16 MiB budget applies to the encoded envelope. Reads also reject
oversized strings and malformed text, trailing JSON, invalid envelopes and invalid
artifact schemas. The Instant tag preserves nanoseconds on JVM and Babashka.

## Validation

Snapshot tests cover date/Instant round trips, collection kinds, modified payloads,
invalid schemas, byte limits, fatal errors and preserved thread interruption.
Publication tests additionally cover Instant persistence and uncached disk reads.
After integrating the bounded-codec fixes, all three artifact consumers pass
38 tests and 140 assertions. The rebuilt packaged CLI passes 24 publication and
snapshot tests with 103 assertions. The scoped standards scanner reports no
violations; lint, Polylith and signed-commit hooks pass. PR budget: 116/600.

## Standards adversarial pass

No duplicated serialization format or artifact constructors. Namespace strata are
zero through two; functions have single responsibilities and localized diagnostics.
Filesystem publication and in-memory snapshot encoding remain separate APIs.

## Prerequisite and scope

Stacked on #1938. Retarget to main only after that PR merges. OPSV checkpoint
integration is a separate application change, not a claim of N7 completion.
