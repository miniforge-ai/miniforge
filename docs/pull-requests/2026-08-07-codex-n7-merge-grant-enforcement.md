<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: enforce grants on PR merge

## Overview

Requires a runtime-issued merge grant and an allowing DecisionEnvelope before
the PR lifecycle can invoke `gh pr merge`.

## Motivation

Merge transactions were durable but still committed through
`:authority/unenforced`, leaving an ambient-authority escape hatch in N7.

## Changes in Detail

- Resolve the exact GitHub repository and preallocate the effect identifier.
- Issue and authorize a merge-scoped grant after provider-backed readiness.
- Derive one DecisionEnvelope through the gate kernel.
- Persist effect, grant, envelope, workflow, repository, and PR bindings before
  the provider effect.
- Recheck the grant from durable proposal scope at commit time.
- Separate authority, transaction, outcome, and orchestration namespaces.
- Deny absent, expired, or mismatched grants without a merge command.
- Preserve provider-exception diagnostics without claiming auto-merge succeeded.

## Testing Plan

- Staged Kondo and stratum lint: zero findings.
- Polylith structure check: zero errors and warnings.
- PR lifecycle: all tests green, including denial and pre-effect durability.
- Pre-commit smoke and GraalVM/Babashka compatibility: green per commit.

## Deployment Plan

No migration is required. Existing unresolved transactions remain
reconcilable; new merges require runtime authority.

## Related Issues/PRs

Implements `work/ariadne-merge-grant-enforcement.spec.edn` after #1706.

## Checklist

- [x] Provider reads, decisions, and effects are separated.
- [x] No final merge path uses `:authority/unenforced`.
- [x] Every changed implementation namespace has at most three strata.
