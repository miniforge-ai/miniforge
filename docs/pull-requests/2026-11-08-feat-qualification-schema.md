<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Qualification report schema and verdict

## Overview

A new `qualification` component holds the values of gate evidence: the
criteria a gate declares, the result a scenario reports per criterion, the
build it ran on, and the report. This PR adds the values and the verdict. The
stacked PR adds build identity, the report writer and the run boundary.

## Motivation

The tracker #2421 issues share one "Gate evidence" section: a repeatable
command, a machine-readable report tied to an immutable build and to fixture
and configuration hashes, the injected faults, the observed state, and pass or
fail for every criterion. Unsupported or unrun behavior is not a pass. One
shared format lets every gate report the same way.

## Changes in Detail

1. `vocabulary`: statuses (`pass`, `fail`, `unrun`, `unsupported`), verdicts
   (`pass`, `fail`, `incomplete`), scalar shapes (SHA-256 hex, git SHA).
2. `schema`: closed Malli maps for `Gate`, `CriterionResult` (status, reason,
   injected faults, observed state), `Row`, `Build`, `Failure`,
   `ScenarioOutput` and `Report`.
3. `verdict`:
   1. `rows` joins each declared criterion with its result, in declaration
      order; a criterion with no result is unrun with a reason.
   2. `verdict` is fail when any row failed, pass only when every row passed,
      incomplete otherwise.
   3. `result-problems` names results for undeclared criteria and duplicates.
4. `interface`: the `Gate`, `ScenarioOutput` and `Report` schemas and
   `valid-report?`.
5. Root `deps.edn`: the component in the `:dev` and `:test` aliases.

## Review fixes (Copilot)

1. A gate's criterion ids must be unique; otherwise one result would count
   for two declared criteria.
2. A report's verdict must be the one its rows give, so a report cannot claim
   a pass over a failed, unrun or unsupported row. The rule moves to
   `vocabulary/verdict-of-statuses` so the schema and `verdict` share it.
3. Only the command's executable must be nonblank; arguments may be any
   string, so a command with an empty argument repeats exactly.
4. `rows` no longer keeps the last of two results for one criterion: a
   criterion with more than one result is unrun, so `rows` is safe even when
   called without `result-problems`.

## Testing Plan

1. `verdict_test`: all-pass is a pass; a missing result is unrun and the
   verdict incomplete; unsupported is not a pass; any failure fails; rows keep
   declaration order and evidence; undeclared and duplicate results are
   problems; gate schema rejects an unqualified id and an empty criteria list.
2. `schema_test`: a complete report is valid; an unknown key, an unknown
   status, a short hash, no criteria or a blank executable is not; a pass
   verdict over a failed, unrun or unsupported row is not; an empty command
   argument is; a gate with a repeated criterion id is not.

## Deployment Plan

None. Nothing calls the component yet.

## Related Issues/PRs

Shared gate-evidence harness for tracker #2421 (first use: the #2422 gate
report). Stacked PR: build identity, writer and `run-gate!`.

## Checklist

- [x] Unrun and unsupported criteria never count as a pass.
- [x] Report is a closed, versioned map.
