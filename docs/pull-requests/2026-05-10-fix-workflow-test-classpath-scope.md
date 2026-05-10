<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): isolate workflow environment promotion phases

## Overview

Finish the workflow-side stable-derived cleanup for environment promotion
by making the integration tests bind explicit phase test support instead
of ambient resource composition.

## Why

`environment_promotion_integration_test` was still assuming project-wide
phase resources were present. Narrowed Polylith project runs exposed
that hidden dependency.

## What changed

- make environment-promotion tests bind the explicit phase test-support
  resource

## Files changed

- `components/workflow/test/ai/miniforge/workflow/environment_promotion_integration_test.clj`

## Verification

- focused `environment_promotion_integration_test`
- `bb pre-commit`
