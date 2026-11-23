<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve explicitly requested chain definitions

## Overview

An exact chain version must not silently fall back to a different resource.
Validate loaded identity and version before returning a selected definition.

## Motivation

N1/N2 require a resolved definition before chain admission and stable recovery identity.
The deployed loader currently substitutes base or latest resources on an exact-version miss.

## Layer

Definition loading boundary and pure selection policy, branched from main `32ba8589`.
Integrates main `1a9150be` for the shared Unicode-aware non-blank version contract.

## Changes in Detail

- Treat explicit versions as exact requests, with no fallback to another version.
- Reject mismatched loaded identities and unresolved definition versions.
- Preserve anomalies as values and the public throwing compatibility boundary.
- Keep resource selection and validation separate from chain execution.
- Separate resource I/O from pure identity/version policy and loader composition.
- Decode filesystem and JAR resource paths correctly when paths contain spaces.
- Share definition fixtures and replace the loader tests' five-stratum graph.

## Testing Plan

Reproduce missing-version and mismatched-definition failures with resource stubs.
Run existing loader regressions, deployed consumers, and packaged checks serially.
Complete the standards pass, normal signed hooks, and exact-head review/CI gates.

The original selection policy failed 20 regression assertions. The resource
integration test passes for both real directory and JAR classpaths containing
spaces (one test, four assertions). Babashka classpaths with spaces also work.
The incremental standards scan reports zero findings; kondo reports zero warnings
or errors. All three workflow consumers (Miniforge, Core, and TUI) pass serially.
The rebuilt CLI passes 17 focused tests / 59 assertions outside the checkout,
using only the artifact and test files, without production-source overlays.
The final real-classpath integration check again passes one test / four assertions.

## Deployment Plan

Callers requesting unavailable exact versions will receive a not-found result.
This intentionally prevents execution of an unintended definition.
Latest ordering, frozen run snapshots, durable admission, and recovery are separate work.

## Related Issues/PRs

Implements part of the resolved-definition obligations from contract PR #2000.
Reuses the shared text contract from merged PR #2008.

## Checklist

- [x] Add regression tests and implementation.
- [x] Validate deployed and packaged behavior.
- [x] Complete adversarial standards review and normal signed hooks.
- Require clean exact-head review and all CI before merge.
