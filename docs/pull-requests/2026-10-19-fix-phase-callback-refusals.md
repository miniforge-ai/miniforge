<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Stop execution when phase boundary callbacks refuse

## Overview

N3 section 9.1 requires a failed durable publication to fail its triggering
operation. The executor currently ignores callback results. Provide the execution
boundary needed to propagate publication anomalies without throwing inside the
component or losing the phase's already-completed effects and result.

## Layer and dependencies

Workflow execution application layer, based on main. Independent of phase-agent
fact projection. The existing public executor arity remains unchanged.

## Changes

- Separate active-interceptor selection from lifecycle and transition execution.
- A start callback anomaly prevents phase entry, gates, leave, and transitions.
- A completion callback anomaly retains the processed phase result but prevents
  DAG activation and normal phase transition.
- Retain the original anomaly, boundary, phase, and diagnostic in execution errors.
- Preserve non-anomaly observer results and DAG short-circuit behavior.
- Preserve machine-entry selection precedence over legacy phase lookup.

## Verification

Test canonical and legacy anomaly results, both refusal boundaries, retained
effects, ordinary observer returns, DAG ordering, and the real public phase path.
All three workflow consumer suites passed serially (1m30s). Focused source and
isolated rebuilt-package tests each passed 4 tests and 48 assertions. The package
test uses only the CLI jar and workflow/phase test directories, outside the repo.
Lint and inferred strata are clean; normal hooks and postcommit standards scans
remain required before pushing.

## Deployment and review gates

Deploy with the normal CLI build; no configuration or data migration is required.
Reverting this PR restores observer-only callback semantics. Review the selection
precedence and refusal ordering, not just formatting: the start boundary precedes
all phase effects. The completion boundary follows result processing but precedes
DAG activation and transitions. No exception is requested for commit or PR size.

- [x] Source consumer and isolated package acceptance
- [x] Adversarial standards pass and inferred-strata check
- [ ] Signed commits, postcommit audit, exact-head review, and all CI gates

## Remaining integration

Lifecycle publishers and callback wrappers must still propagate their publication
failures to this boundary. This change alone does not establish durable receipts
for legacy streams or switch the default host journal.
