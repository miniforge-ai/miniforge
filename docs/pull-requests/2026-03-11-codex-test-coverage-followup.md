<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: harden state profile extraction coverage

## Overview

Adds targeted regression tests around the recent state-profile extraction so the fallback and normalization paths are
explicitly covered.

## Motivation

The resource-backed profile move is correct, but the first pass exposed an easy-to-miss bug: transition targets loaded
from EDN arrived as vectors, which broke membership checks until they were normalized to sets. This PR locks down those
seams so later extraction work has tighter safety rails.

## Changes in Detail

- Added a test that proves unknown profiles fall back to the configured default profile.
- Added a test that proves EDN-shaped transition targets are normalized before the DAG state machine uses them.
- Kept the existing classpath-overlay coverage that verifies the software-factory registry remains active on the
  flagship app classpath.

## Testing Plan

- `bb pre-commit`

## Deployment Plan

Merge normally. Test-only change.

## Related Issues/PRs

- Follow-up to PR #292

## Checklist

- [x] Cover fallback profile resolution
- [x] Cover resource/EDN normalization path
- [x] Keep classpath overlay coverage intact
- [x] Re-run pre-commit
