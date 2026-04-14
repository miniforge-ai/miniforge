<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Artifact Component with Datalevin Storage

**Branch:** `feat/artifact-component`
**Date:** 2026-01-19

## Summary

Implements the `artifact` component for Phase 1 (Domain), providing artifact storage with provenance tracking using
Datalevin in-memory database.

## Changes

- `components/artifact/deps.edn` - Datalevin 0.9.16 dependency
- `components/artifact/src/ai/miniforge/artifact/core.clj` - Core implementation with ArtifactStore protocol and
  Datalevin backend
- `components/artifact/src/ai/miniforge/artifact/interface.clj` - Public API
- `components/artifact/test/ai/miniforge/artifact/interface_test.clj` - 34/35 tests passing
- `deps.edn` - Updated with artifact component paths

## Design Decisions

1. **Datalevin for storage**: In-memory by default, can switch to persistent LMDB or swap to XTDB/Datomic
2. **ArtifactStore protocol**: Pluggable storage backends
3. **Provenance tracking**: Parent/child links between artifacts
4. **Phase 1 scope**: Simple filter-based queries (Datalog queries in Phase 2)

## Test Status

34/35 assertions passing. One failing test for multi-criteria queries will be fixed in follow-up.

## Dependencies

- `datalevin/datalevin` 0.9.16
- `ai.miniforge/schema` (local)
- `ai.miniforge/logging` (local)
