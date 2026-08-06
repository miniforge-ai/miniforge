<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: register OPSV project integration coverage

## Overview

Register the existing OPSV lifecycle integration namespace in the project-level
test runner so CI loads and executes it.

## Motivation

The lifecycle integration test lived under the Miniforge project but was absent
from the explicit namespace list used by `bb test:integration`. It also relied
on a component test resource that is not transitively available from the
project classpath. As a result, the test was both skipped by CI and unable to
load when invoked directly from the project.

## Layer

Test infrastructure.

## Changes in Detail

- Register the OPSV lifecycle integration namespace in the project test list.
- Resolve its canonical component fixture from either the root test classpath
  or the project test runner's working directory.
- Apply the enforced stratified-design annotations and ordering to the touched
  test-runner namespace.

## Validation

- Direct OPSV lifecycle integration run: 4 tests, 14 assertions.
- Changed-file Clojure lint: 0 errors, 0 warnings.
- Changed-file stratum lint: 0 findings after autofix.
- Poly check: only the four known repository baseline warnings.
- Pre-commit smoke: 339 tests, 1285 assertions.
- GraalVM compatibility: 8 tests, 602 assertions.

## Checklist

- [x] The existing test is loaded by the integration task.
- [x] Fixture resolution works from the project runner working directory.
- [x] No test data is duplicated.
- [x] The shared test-runner namespace remains within three computed strata.

## Follow-up

The dependent N7 event-projection PR expands this registered test with event
and durable evidence assertions.
