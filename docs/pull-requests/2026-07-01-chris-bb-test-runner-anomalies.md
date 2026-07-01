# Refactor: return bb test runner parse failures as data

## Overview

This PR cleans up the actionable exceptions-as-data sites in
`bb-test-runner.core`.

## Motivation

The stable-derived test runner still throws for parse/derivation failures that
can be represented as data. Keeping those paths data-first lets callers decide
whether to surface a CLI boundary error or recover.

## Changes in Detail

- Add data-returning parsing helpers for project-list and diagnostic arguments.
- Keep existing public parse functions compatible where callers still expect
  plain values.
- Reclassify the JVM-only `run-all` placeholder as a programmer-error guard if
  appropriate.
- Update focused bb-test-runner tests.

## Testing Plan

- Run focused bb-test-runner tests.
- Run the exceptions-as-data scanner for `bb_test_runner/core.cljc`.
- Run `bb pre-commit`.

## Deployment Plan

No deployment special handling. This affects local test-runner helper behavior
only.

## Related Issues/PRs

- Continues the exceptions-as-data cleanup waves after #1338.
- Independent of #1339.

## Checklist

- [x] Focused bb-test-runner tests pass.
- [x] `bb_test_runner/core.cljc` scanner cleanup-needed count reaches zero.
- [x] `bb pre-commit` passes.
