<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: A quality rule that fails to run is an execution error

## Overview

A data-quality rule whose check fn throws, or returns anything but a map with
a boolean `:valid?`, now fails its validate stage as an execution error. It
never reads as a pass, and never as a record the rule rejected. This closes
the last code criterion of #2422.

## Motivation

`dq/evaluate-record` called each rule's check fn and merged the result over
the rule's id. A throw escaped `execute-pipeline` uncaught. A verdict of
`{:valid? "yes"}` was truthy, so the record passed. A nil verdict had no
`:valid?`, so the record was counted as a domain violation.

## Changes in Detail

1. `data-quality.invoke` (new): `evaluate-rule` calls the check fn through a
   catch-and-convert helper. A throw becomes `:rule/error` with
   `:error/kind :rule/threw` and the exception class and message. A malformed
   verdict becomes `:error/kind :rule/malformed-verdict` with the verdict's
   type, not its value, so record contents stay out of the error. Both set
   `:valid? false`.
2. `data-quality/rule-errors` (new): every rule evaluation that failed to run,
   as `{:record-index :rule/id :error/kind ...}`.
3. `pipeline-runner.processing-stage`: when `rule-errors` is non-empty, the
   validate stage fails with `:error-kind :execution` and
   `:quality/rule-errors`; otherwise it filters and reports as before.

## Behavior changes

1. A throwing rule fails its stage with a structured result instead of
   escaping `execute-pipeline`.
2. A rule returning nil, a non-map, or a non-boolean `:valid?` fails its stage.
   Before, nil counted as a rejected record and a truthy `:valid?` as a pass.
   The four built-in rules return literal booleans and are unaffected.

## Testing Plan

1. `rule_errors_test` (new): a throwing rule yields one `:rule/threw` error
   per record with its exception type; nil, `true`, `{:valid? "yes"}` and a map
   without `:valid?` yield `:rule/malformed-verdict`; a malformed verdict never
   passes a record; a rule that ran and rejected a record is a violation, not
   a rule error.
2. `preflight_test`: a throwing rule and a malformed verdict each fail the
   validate stage with `:error-kind :execution`, and the sink is never called.
3. Locally, data-quality, pipeline-runner, pipeline-config and ETL namespaces:
   100 tests, 316 assertions, 0 failures.

## Deployment Plan

None. A custom rule must return a map with a boolean `:valid?`.

## Depends on

Stacked on #2448, which is stacked on #2446 and #2444.

## Related Issues/PRs

Part of #2422, under tracker #2421. The #2422 gate report waits for the shared
qualification harness.

## Checklist

- [x] Throw and malformed verdict become execution errors.
- [x] Domain violations stay distinct from rule errors.
