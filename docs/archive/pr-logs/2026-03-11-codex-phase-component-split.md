<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Split software-factory phase implementations from kernel phase registry

## Summary

This PR moves the shipped SDLC phase implementations out of the shared `phase`
component and into a new `phase-software-factory` component.

The kernel `phase` component now owns only:

- the phase registry and schemas
- generic status helpers
- generic resource-driven phase implementation loading

The flagship app phase set is now composed via classpath resources instead of
hardcoded `require`s in `ai.miniforge.phase.interface`.

## Changes

### New component

- Added `components/phase-software-factory`
- Moved shipped SDLC phase implementations there:
  - `explore`
  - `plan`
  - `implement`
  - `verify`
  - `review`
  - `release`
- Moved their behavior tests with them

### Kernel loader seam

- Added `ai.miniforge.phase.loader`
- `ai.miniforge.phase.interface` now loads phase implementations from
  `config/phase/namespaces.edn` resources on the active classpath
- Removed hardcoded implementation `require`s from the kernel interface

### App composition

- Added `components/phase-software-factory/resources/config/phase/namespaces.edn`
- Wired `phase-software-factory` into:
  - `bases/cli`
  - `projects/miniforge`
  - `projects/miniforge-tui`
  - root `deps.edn` dev/test aliases

## Why

This is the phase-side follow-up to the workflow-family split.

It creates the right open-core seam:

- kernel phase registry remains generic
- shipped software-factory phase implementations become app-owned
- future apps can provide a different phase set through the same interface and
  resource pattern

## Test coverage

Existing behavior coverage remains in place through:

- moved SDLC phase behavior tests in `phase-software-factory`
- project integration tests that still execute real workflows end-to-end

New seam-specific coverage was added in the kernel:

- `ai.miniforge.phase.loader-test`
  - resource-driven namespace discovery
  - registration of configured phase implementations
- `ai.miniforge.phase.interface-test`
  - generic interface behavior using a synthetic registered phase instead of
    assuming SDLC phases live in the kernel

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase.interface-test 'ai.miniforge.phase.loader-test
  'ai.miniforge.phase.implement-test 'ai.miniforge.phase.verify-test 'ai.miniforge.phase.release-test
  'ai.miniforge.phase.review-repair-loop-test 'ai.miniforge.phase.verify-failure-modes-test) ..."`
- `bb test`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`

## Follow-up

The next boundary cut on this track is the remaining software-factory helper
surfaces that still assume SDLC/PR semantics in shared workflow-facing APIs.
