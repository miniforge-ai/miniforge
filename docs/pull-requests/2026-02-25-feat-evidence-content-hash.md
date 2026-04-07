<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat/evidence-content-hash — PR6b

## Layer

Domain (Evidence Bundle)

## Depends on

- None (independent)

## Overview

Adds SHA-256 content hashing for evidence artifacts, enabling tamper detection
and deduplication in evidence bundles.

## Motivation

Evidence bundles attest to what happened during a workflow run. Without content
hashes, there is no way to verify that an artifact has not been modified after
collection, and no efficient way to deduplicate identical artifacts across
runs. SHA-256 provides a standard, collision-resistant hash that serves both
integrity verification and deduplication.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/evidence-bundle/src/ai/miniforge/evidence_bundle/hash.clj` | New file — `content-hash` fn computing SHA-256 hex digest of byte content |
| `components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj` | Wired `content-hash` into `assemble-evidence-bundle` to compute `:evidence/content-hash` for each artifact |
| `components/evidence-bundle/src/ai/miniforge/evidence_bundle/interface.clj` | Exported `content-hash` via component interface |
| `components/evidence-bundle/test/ai/miniforge/evidence_bundle/hash_test.clj` | 5 new tests covering known-vector hash, empty input, determinism, different-input divergence, and large-input handling |

**+89 LOC** across 4 files.

## Testing Plan

- [x] 5 unit tests for `content-hash` covering known vectors, edge cases, and determinism
- [x] Pre-commit hooks pass (lint, format, test, graalvm)
- [ ] Integration verification when evidence bundles are consumed downstream

## Deployment Plan

Additive only — new function and automatic hash field on evidence artifacts.
No breaking changes. Ships with next release.

## Related Issues / PRs

- Independent — no blockers or dependents in Wave 1
- Part of the meta-loop evidence integrity work

## Checklist

- [x] Tests written and passing
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected
- [x] Babashka / GraalVM compatible
