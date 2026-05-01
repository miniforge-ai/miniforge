<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat/dependency-attribution-taxonomy

## Summary

Adds the first implementation slice for the external dependency health and
failure attribution stack introduced in `#696`.

This PR keeps the scope narrow:

- adds canonical dependency-attribution enums to failure-classifier config
- exposes config-backed schemas and predicates for dependency attribution
- adds small factory functions for canonical dependency attribution records
- adds test coverage for the new taxonomy surface

It does **not** yet change runtime classification behavior. The next PR will
wire the classifier engine to return and consume this richer attribution data.

## Why This Slice First

Before Miniforge can distinguish provider/platform/setup failures from product
failures in reliability, supervision, CLI output, or behavioral evidence, it
needs one authoritative data model for:

- where a failure came from
- what kind of dependency issue it was
- whether it is retryable or requires operator action

This PR establishes that vocabulary in `failure-classifier`, where the rest of
the stack can depend on it cleanly.

## Key Changes

- extended [rules.edn](../../components/failure-classifier/resources/config/failure-classifier/rules.edn)
  with config-backed canonical sets for:
  - `:failure-sources`
  - `:dependency-vendors`
  - `:dependency-classes`
  - `:dependency-retryabilities`
- added new schemas and predicates in
  [taxonomy.clj](../../components/failure-classifier/src/ai/miniforge/failure_classifier/taxonomy.clj)
  for dependency attribution and classified dependency failures
- exported the new public API from
  [interface.clj](../../components/failure-classifier/src/ai/miniforge/failure_classifier/interface.clj)
- added constructor/predicate coverage in
  [classifier_test.clj](../../components/failure-classifier/test/ai/miniforge/failure_classifier/classifier_test.clj)

## What This PR Does Not Do

- no classifier-engine behavior change yet
- no reliability or supervisory-state integration yet
- no event-stream changes yet
- no CLI or dashboard attribution yet

Those are intentionally kept for the next PRs in the stack.

## Validation

- `clj-kondo --lint` on touched failure-classifier files
- `bb test components/failure-classifier`
- full `bb pre-commit`
