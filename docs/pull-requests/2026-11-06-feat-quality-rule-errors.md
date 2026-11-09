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
   runtime class name, not its value, so record contents stay out of the
   error. Both set `:valid? false`.
2. `data-quality/rule-errors` (new): every rule evaluation that failed to run,
   as `{:record-index :rule/id :error/kind ...}`.
3. `pipeline-runner.processing-stage`: when `rule-errors` is non-empty, the
   validate stage fails with `:error-kind :execution` and
   `:quality/rule-errors`; otherwise it filters and reports as before.

## Review fixes (Copilot)

1. `filter-passed` keeps out a record whose rule failed to execute,
   whatever the rule's severity; warning violations still pass.
2. `generate-report` lists rule errors under `:report/rule-errors`, apart
   from violations, and counts their records as failed.
3. The rule boundary also catches `AssertionError`, so a rule's failed
   `assert` becomes `:rule/threw`; other JVM errors stay fatal.
4. The boundary wraps the check fn's return value in a tag, so no verdict
   can select the thrown path; a verdict's own `:rule/error` is dropped, so
   a rule that ran cannot report itself as failed to run.
5. The malformed-verdict error records `(class verdict)`, not `type`, which
   reads `:type` metadata a verdict could fill with record data.
6. The field summary counts rule errors against their field (`:rule-errors`
   beside `:violations`), and a field's pass rate counts each failed record
   once, however many rules on the field failed it.

Final review pass:

1. The interface docstrings for `filter-passed` and `generate-report` describe
   rule errors.
2. The test that a verdict cannot select the thrown path now returns the
   boundary's own thrown tag.
3. `data-quality` `core.clj` and `interface.clj` get the Apache header.

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
   a rule error. Also: a failed `assert`, a warning-severity rule error, the
   report's rule errors and field summary, distinct failed records in a pass
   rate, and verdicts that try to inject a `:rule/error` or the thrown tag.
2. `preflight_test`: a throwing rule and a malformed verdict each fail the
   validate stage with `:error-kind :execution`, and the sink is never called.
3. Locally, on main plus the executor PR: data-quality, pipeline-runner,
   pipeline-config, ETL and workbench-etl-adapter namespaces, 140 tests, 514
   assertions, 0 failures.

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
