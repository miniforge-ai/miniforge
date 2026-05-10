<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): bind software-factory phase tests to explicit loader config

## Overview

Make the software-factory phase tests use the same explicit phase loader
resource boundary as the workflow tests.

## Why

Stable-derived runs exposed that several software-factory tests still
called `phase/get-phase-interceptor` or `phase/phase-defaults` without
binding the test-support loader resource first. That leaked a
production-only dependency on `workflow-compliance-scanner.phases`.

## What changed

- bind `loader/phase-loader-config-resource` in the software-factory
  test family
- reset phase-loader state around each test
- clean the `release_test` missing `clojure.string` require

## Files changed

- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/artifact_persistence_test.clj`
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/implement_test.clj`
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/release_test.clj`
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/review_repair_loop_test.clj`
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/verify_failure_modes_test.clj`
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/verify_test.clj`

## Verification

- narrowed-scope software-factory test runs
- `bb pre-commit`
