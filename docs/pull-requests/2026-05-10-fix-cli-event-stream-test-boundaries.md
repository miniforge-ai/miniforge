<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): restore CLI and event-stream test boundaries

## Overview

Move the progress-display tests back to the CLI base boundary and keep
the event-stream tests focused on event-stream behavior only.

## Why

The old event-stream progress integration test imported a CLI-base seam.
That was an illegal cross-boundary dependency and only stayed green
because wider project test runs masked it.

## What changed

- move CLI progress display coverage into the CLI base test namespace
- remove the CLI seam dependency from the event-stream test
- keep equivalent lifecycle assertions at the correct boundary

## Files changed

- `bases/cli/test/ai/miniforge/cli/workflow_runner/display_output_test.clj`
- `components/event-stream/test/ai/miniforge/event_stream/progress_integration_test.clj`

## Verification

- focused CLI/event-stream tests
- `bb pre-commit`
