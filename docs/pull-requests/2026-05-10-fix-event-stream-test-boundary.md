<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): remove CLI display dependency from event-stream tests

## Overview

Remove the CLI-base display dependency from the `event-stream` project
tests so narrowed Polylith project runs can load the namespace without a
CLI base on the classpath.

## Why

`progress_integration_test` was still requiring
`ai.miniforge.cli.workflow-runner.display`. That made the `event-stream`
test namespace invalid in projects like `data-foundry`, where the CLI
base is not present.

## What changed

- remove display-formatter and progress-listener coverage from the
  `event-stream` integration test namespace
- keep the event-stream test focused on event publication, ordering, and
  subscriber behavior only

## Files changed

- `components/event-stream/test/ai/miniforge/event_stream/progress_integration_test.clj`

## Verification

- focused `event-stream` integration test loading through affected
  projects
- `bb pre-commit`
