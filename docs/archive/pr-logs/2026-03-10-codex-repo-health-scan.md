<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Repo Health Scan Cleanup

**Branch:** `codex/repo-health-scan`
**Base:** `main`
**Date:** 2026-03-10
**Layer:** Application / Adapter cleanup

## Overview

Reduces accumulated repo-health drift by cleaning current source lint warnings
and decomposing oversized interface namespaces while preserving each
`interface.clj` file as the canonical Polylith component boundary.

## Motivation

The audit surfaced two concrete classes of problems:

- source namespaces with active lint warnings
- very large `interface.clj` namespaces that have drifted into "god interface"
  territory, making the public boundary harder to reason about and maintain

This work keeps the public API stable but restructures the implementation
surface underneath it so the interface remains the boundary rather than the
place where all public logic accumulates.

## Changes In Detail

- fix source lint warnings in the currently affected `response`,
  `self-healing`, `tui-engine`, and `tui-views` namespaces
- split oversized `agent`, `event-stream`, `workflow`, and `policy-pack`
  interface namespaces into focused `...interface.*` subnamespaces
- keep `interface.clj` as the canonical public entrypoint with thin forwarding
  and composition
- preserve behavior while reducing namespace size and layer sprawl

## Testing Plan

- [x] `clj-kondo --lint ...` for affected sources
- [x] `bb scripts/test-changed-bricks.bb`
- [x] spot-check public interface entrypoints through component test suites

## Deployment Plan

No special deployment steps.

## Related Issues/PRs

- follow-up to the merged agent/model default cleanup work

## Checklist

- [x] PR doc added
- [x] source lint warnings cleaned up
- [x] oversized interface namespaces decomposed
- [x] verification passing
