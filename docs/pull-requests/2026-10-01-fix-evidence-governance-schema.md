<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Validate portable knowledge and gate evidence

## Scope

Add the missing N6 knowledge-input and gate-execution contracts to the shared
root schema. This is a bounded prerequisite for canonical validation in #1959.
Its canonical entry point consumes the shared schema; this prerequisite alone
does not claim complete evidence enforcement or production gate capture.

## Design

Keep record definitions separate from root validation and reuse shared vocabularies.
Move the canonical predicate boundary prerequisite here so present nil values
are not skipped; malformed records fail validation and fatal errors propagate.
Validate present values without inventing absent knowledge or gate history.
Use N4 section 3.3 for recorded gate violations, as required by N6 section 2.13.
Gate records check exact resolved versions, pack hashes, bindings and waiver joins.
Waived rules must remain in the violations; waived gates cannot masquerade as passed.
Keep legacy supervisory entities separate from the portable N6 record shape.

## Verification

Exercise complete records, missing fields, malformed collections and nested values.
Run affected consumers and rebuilt packaged tests serially before publishing.
Require adversarial standards review, normal hooks, current-head review and all CI.
All three evidence consumers pass. Rebuilt packaged regressions pass seven tests
and 84 assertions. Kondo and inferred namespace strata pass without warnings.
