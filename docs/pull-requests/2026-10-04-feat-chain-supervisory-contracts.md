<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: reconcile chain and supervisory event contracts

## Overview

Preserve chain execution and supervisory capabilities through explicit contracts.
This is a specification foundation, not a runtime conformance claim.

## Motivation

The product owner approved this direction on 2026-10-04: preserve deployed
capabilities through explicit N1/N3 reconciliation, without relaxing validation.
Chain steps and dependency edges represent different facts. Supervisory commands
and materialized snapshots have different owners and replay semantics.

## Layer

Contract foundations. Base branch: main at `9000a049`, after PR #1999 merged.
That PR supplies the separate prose-hygiene prerequisite.

## Changes in Detail

- Define chain execution identity, steps, edges, and event ownership.
- Define supervisory spec records and intervention lifecycle event boundaries.
- Reconcile scope, registry, emission, and compatibility requirements.
- Preserve the separate prose-hygiene prerequisite without repeating its changes.
  No lint settings or budgets are relaxed.

## Testing Plan

Adversarial cross-spec review, Markdown/prose lint, normal commit hooks,
exact-head Copilot review, and all required CI checks precede merge.
Runtime enforcement and negative regressions follow in separate code PRs.

Local Markdown and plainspeak checks pass across all six files. The adversarial
pass traced repeated invocations, missing workflow targets, intervention admission
versus approval, replay ownership, and ambiguous legacy records. A contract-only
change does not claim runtime tests prove the newly specified behavior.

Review corrections preserve ambiguous legacy IDs without reinterpretation and
require child-run UUIDs even for one-step chains and pre-execution failures.
The Spec projection retains structured intent, string/keyword tags, and the
WorkflowRun-owned foreign key; none of these fields grants execution authority.
Historical scope profiles are explicit and retained edge events keep Workflow scope.
New writes require a scope discriminator; a payload version alone cannot change scope.
N5's required justification remains required, with producer and migration obligations
made explicit. The lifecycle does not add an approved-to-rejected transition.

## Deployment Plan

No runtime behavior changes. Schema/scope enforcement precedes live publication
and durable evidence checkpoint integration.

## Related Issues/PRs

PRs #1985 and #1957 supply publication and sealing prerequisites.
PR #1999 repairs pre-existing prose violations without altering contracts.
PRs #1956, #1952, and #1948 remain downstream evidence integration work.

## Checklist

- [x] Reconcile contracts without weakening guarantees.
- [x] Complete standards and adversarial review.
- Require normal signed hooks, clean exact-head review, and all CI before merge.
