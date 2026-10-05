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

Definition loading boundary and pure selection policy, based on main `32ba8589`.
This is independent of the event payload foundations.

## Changes in Detail

- Treat explicit versions as exact requests, with no fallback to another version.
- Reject mismatched loaded identities and unresolved definition versions.
- Preserve anomalies as values and the public throwing compatibility boundary.
- Keep resource selection and validation separate from chain execution.

## Testing Plan

Reproduce missing-version and mismatched-definition failures with resource stubs.
Run existing loader regressions, deployed consumers, and packaged checks serially.
Complete the standards pass, normal signed hooks, and exact-head review/CI gates.

## Deployment Plan

Callers requesting unavailable exact versions will receive a not-found result.
This intentionally prevents execution of an unintended definition.
Latest ordering, frozen run snapshots, durable admission, and recovery are separate work.

## Related Issues/PRs

Implements part of the resolved-definition obligations from contract PR #2000.

## Checklist

- [ ] Add regression tests and implementation.
- [ ] Validate deployed and packaged behavior.
- [ ] Complete adversarial standards review and normal signed hooks.
- Require clean exact-head review and all CI before merge.
