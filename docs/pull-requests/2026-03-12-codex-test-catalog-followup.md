<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Test Catalog Follow-Up

## Layer

Testing

## Depends on

- #308 (`codex/messages-catalog-split`) — merged

## What this adds

- removes duplicated resource-owned CLI copy/config values from tests that were still asserting against hardcoded
  English literals
- updates CLI tests to read expected help/title/example output through `app-config` and `messages`
- updates recommendation prompt tests to read expected prompt vocabulary from the same resource-backed config the
  runtime
  uses
- updates the kernel CLI integration test to assert against the active project-owned app profile instead of duplicating
  kernel-specific copy in test literals

## Strata affected

- `bases/cli` tests — app profile, messages, help output, workflow-runner output, and recommendation prompt coverage
- `projects/workflow-kernel` tests — kernel help/config assertions now read from the active app profile and message
  catalog

## Why

- after the message-catalog split, a number of tests still carried their own copies of user-facing strings
- that made the tests a second source of truth for English copy instead of consumers of the resource seam
- if locale support expands later, these tests need to be catalog/config-driven rather than pinned to inline literals

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.app-config-test 'ai.miniforge.cli.messages-test
  'ai.miniforge.cli.main-test 'ai.miniforge.cli.workflow-runner.display-test
  'ai.miniforge.cli.workflow-recommendation-config-test 'ai.miniforge.cli.workflow-recommender-test)
  (clojure.test/run-tests 'ai.miniforge.cli.app-config-test 'ai.miniforge.cli.messages-test
  'ai.miniforge.cli.main-test 'ai.miniforge.cli.workflow-runner.display-test
  'ai.miniforge.cli.workflow-recommendation-config-test 'ai.miniforge.cli.workflow-recommender-test)"`
- `clojure -M -e "(require 'ai.miniforge.workflow.kernel-loader-integration-test)
  (clojure.test/run-tests 'ai.miniforge.workflow.kernel-loader-integration-test)"` from `projects/workflow-kernel`
