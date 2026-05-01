<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Dependency Attribution Classifier

## Summary

This PR wires the canonical dependency-attribution model into
`failure-classifier` without breaking the existing `classify` API.

The classifier can now return rich classified failure records that distinguish:

- Miniforge product failures
- user environment and setup failures
- external provider failures
- external platform failures

## Changes

- adds canonical classified failure record construction in
  [taxonomy.clj](components/failure-classifier/src/ai/miniforge/failure_classifier/taxonomy.clj)
- adds rich classifier APIs in
  [interface.clj](components/failure-classifier/src/ai/miniforge/failure_classifier/interface.clj):
  - `classify-record`
  - `classify-exception-record`
- extends
  [classifier.clj](components/failure-classifier/src/ai/miniforge/failure_classifier/classifier.clj)
  to compile and apply dependency attribution patterns from the existing
  `external.edn` and `backend-setup.edn` resources
- upgrades the dependency pattern resources to canonical machine-oriented data in:
  - [resources/error-patterns/external.edn](resources/error-patterns/external.edn)
  - [resources/error-patterns/backend-setup.edn](resources/error-patterns/backend-setup.edn)
- keeps the duplicated `agent-runtime` resource copies in sync so classpath
  resolution cannot silently pick stale pattern data
- adds representative coverage in
  [classifier_test.clj](components/failure-classifier/test/ai/miniforge/failure_classifier/classifier_test.clj)
  for:
  - provider outage
  - provider rate limit
  - account limitation
  - local permission failure
  - missing API key
  - GitHub and Kubernetes platform failures

## Why

Dogfood runs exposed an important operational gap: Miniforge could tell that a
workflow failed, but not whether the failure was caused by Miniforge itself,
the user environment, an LLM provider, or an external platform.

This PR closes the first functional part of that gap by making failure
classification return canonical attribution data instead of only a single
failure-class keyword.

## Validation

- `clj-kondo --lint components/failure-classifier/src/ai/miniforge/failure_classifier
  components/failure-classifier/test/ai/miniforge/failure_classifier resources/error-patterns
  components/agent-runtime/resources/error-patterns`
- targeted `ai.miniforge.failure-classifier.classifier-test`
- full `bb pre-commit`
