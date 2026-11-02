<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(deliberation-workspace): named constants carry docstrings

## Overview

Restores intent docstrings on the deliberation-workspace named constants.
Several constant docstrings were turned into `;;` comments in #2331 so that PR
stayed under the 600-line budget, and two constants never had one. Rule 006
(`foundations/named-constants`) requires a docstring on each, saying what the
value means and why it was chosen.

## Motivation

`named-constants` (dewey 006, `alwaysApply`, hard-halt) says a named `def`
carries a docstring explaining its intent, and `documentation-discipline`
(dewey 600) asks that 006 docstrings be preserved verbatim. A `;;` comment
above a `def` is not attached to the var, so `doc` and tooling cannot see it.
The budget pressure that caused the conversion is gone now that #2331 has
merged.

## Changes in Detail

- These comments move back into the `def` as docstrings, with the text
  unchanged:
  - `semantics`: `creation-types`, `target-types`, `operation-keys`,
    `claim-kinds`, `default-statement-cap`;
  - `conflict`: `conflict-id-prefix`;
  - `admission`: `operation-stages`;
  - `validation`: `permission-stages`.
- Two constants had no docstring at all and now have one:
  - `semantics/constraint-kinds`, against N14 §2.4: `:hard` constraints come
    only from the specification or the user, and agents may propose `:soft`
    ones;
  - `object/deliberative-statuses`, against N14 §2.3: the statuses shared by
    claims, hypotheses, plans and decisions.
- Behaviour is unchanged: only docstrings move.

## Testing Plan

Ran locally on plain Clojure 1.12 with a local malli stand-in, because Clojars
is unreachable from the authoring sandbox:

- all 12 deliberation-workspace suites: 171 tests, 552 assertions,
  0 failures;
- clj-kondo 2026.10.26 (the CI version) on `bases components development/src`:
  0 errors.

`poly test` and the Polylith check ran only in CI.

## Deployment Plan

None.

## Related Issues/PRs

- Follow-up to #2331 (semantic admission), where the conversion happened.
- The open stacked PRs #2335 and #2332 keep their constants' docstrings.

## Checklist

- [x] No behaviour change; docstrings only
- [x] Every named constant in the component carries a docstring
- [x] Copyright header on the PR doc
