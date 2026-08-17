<!--
  Title: Normalize deployment provider operations
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(deploy): expose normalized provider operations

## Layer

Adapter.

## Depends on

None.

## Overview

Defines the injectable Kubernetes operation map used by the governed deployment
flow and makes rollback capture obey the same canonical success/failure result
contract as render, dry-run, and apply operations.

## Changes

- Expose target resolution, render, server dry-run, rollback capture, exact-byte
  apply, and observation as one immutable operation map.
- Return a schema success or failure from rollback capture, including shell and
  rollback-shape failures.
- Keep the existing deployment flow compatible with the normalized result.
- Test result preservation, exact rendered-byte forwarding, and both rollback
  failure boundaries.

## Design

- Simple: the operation map is plain data and carries no cached manifest or
  mutable provider state.
- Separated: provider mechanics stay in the adapter; the later application flow
  receives replaceable functions.
- Canonical: callers use `schema/succeeded?` and `schema/failed?` rather than
  inspecting result-map structure.

## Verification

- `bb pre-commit`
- `clojure -M:poly test brick:phase-deployment`
- Adversarial review against Clojure, result-handling, testing, component, and
  stratified-design standards.
