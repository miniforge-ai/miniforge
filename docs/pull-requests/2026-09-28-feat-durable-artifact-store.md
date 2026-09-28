<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Confirm immutable artifact publication

## Overview

Add synchronous, uncached publication and read APIs for immutable evidence artifacts.
The existing mutable ArtifactStore protocol retains its behavior.

## Motivation

The Transit store acknowledges asynchronous writes before persistence finishes.
N6 evidence references need a confirmed durable record, not a cache hit.

## Layer and dependencies

The artifact component owns filesystem and Transit publication primitives.
Application callers use its validated public interface. The shared content-hash
component supplies the integrity digest.

## Changes in detail

- Validate artifact records and canonical, existing directory paths.
- Encode round-trippable Transit data with a 16 MiB retained-output limit.
- Store a versioned envelope with a canonical content digest. Verify the digest
  on every read and retry; it detects corruption, not malicious writer replacement.
- Force the complete temporary file, create an immutable hard link, then verify
  exact content and force the destination and ancestor directories.
- Never replace an existing ID. Identical retries repeat durability barriers;
  different content returns a conflict.
- Read from disk without consulting the legacy mutable-store cache.
- Return localized anomalies for codec, filesystem and boundary failures.
- Preserve interruption and return the artifact ID for unconfirmed-write recovery.

## Testing plan

All three artifact-consuming projects pass 26 tests and 83 assertions each.
The hardened publication suite passes 12 tests and 46 assertions on the JVM
and packaged Babashka CLI. Tests cover disk rereads, conflicting and concurrent
publication, pre-link failure, uncertain force, identical retry, invalid paths,
symlinks, unsupported content, output limits and interrupted/error boundaries.
The rebuilt CLI jar is 38,961,776 bytes. Scoped standards report zero violations across 19 files.
Trailing JSON or malformed bytes are rejected on read and retry. Corrupt reads
return faults; fatal runtime errors return non-retryable fatal anomalies.
Relative directories, malformed UTF-8 and schema-valid content corruption are refused.

## Standards adversarial pass

Separate codec, filesystem, publication and exception-conversion responsibilities.
Keep namespaces within three dependency strata and share one failure constructor.
The interface validates external inputs; messages use the component catalog.
No polling, asynchronous acknowledgement, duplicated artifact schema or cache assumption.

## Deployment plan

The host must exclusively control the directory and its ancestors. Hard links and
directory force support are required; unsupported filesystems fail closed.
This API does not defend against a privileged process changing host-owned paths
concurrently. No existing store is migrated or production configuration changed.
Application evidence assembly/finalization wiring follows separately.

## Related issues/PRs

N6 evidence durability and N7 OPSV implementation completion.

## Checklist

- [x] Focused, all-consumer and packaged tests pass
- [x] Scoped standards pass and adversarial review completed
- [ ] Final-head review settled and all CI checks green
