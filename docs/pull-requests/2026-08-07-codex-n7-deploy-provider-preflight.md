<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add exact deployment preflight operations

## Overview

Adds the provider operations needed to govern Kubernetes deployment without
changing the active deploy flow yet.

## Motivation

N7 must persist an exact target and provider dry-run before mutation. Rebuilding
or resolving the current Kubernetes context again at commit would let the
authorized proposal differ from the bytes or cluster actually mutated.

## Layer

Kubernetes provider adapter.

## Depends on

- #1712 — deployment flow decomposition (merged)

## Changes in Detail

- Resolve a context-free target to kubectl's configured current context once.
- Expose separate render, server-dry-run, and apply-rendered provider calls.
- Pass identical rendered manifest bytes to dry-run and real apply.
- Preserve the existing combined apply operation until enforcement replaces it.

## Testing Plan

- Phase-deployment component tests.
- Staged Kondo and stratum lint with zero findings.
- Polylith structure check with zero errors and warnings.
- Full pre-commit smoke and compatibility suites.

## Deployment Plan

Merge before the deployment grant-enforcement PR. This adds provider seams but
does not change production deployment behavior.

## Related Issues/PRs

Prerequisite adapter slice for `work/ariadne-deploy-grant-enforcement.spec.edn`.

## Checklist

- [x] Current context becomes an exact target value.
- [x] Server dry-run and apply consume identical manifest bytes.
- [x] Existing deploy behavior remains independently usable.
