<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix: remove ad hoc supervision payload duplication

## Overview

Tighten the merged workflow supervision boundary by replacing duplicated
hand-built monitoring payload maps with small factory helpers in
`workflow.monitoring`.

## Motivation

The supervision-boundary refactor landed the right architecture, but
`workflow.monitoring` still had two regressions against the standards:

- progress-monitor creation was split across ad hoc call sites
- supervision halt payloads were hand-built inline in multiple shapes

This keeps the slice small while restoring the factory/predicate style the
repo expects.

## Changes In Detail

- Add `progress-monitor-config` and `create-progress-monitor`
  - default and configured supervisor creation now share one factory path
- Add `supervision-halt-data`, `supervision-halt-error`, and
  `supervision-halt-response`
  - `handle-supervision-halt` now consumes those factories instead of
    hand-constructing duplicate maps inline
- Extend `monitoring_test`
  - default progress-monitor creation uses canonical config
  - workflow overrides merge through the same factory
  - halt handling emits the expected canonical error/response payloads

## Testing Plan

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/monitoring.clj
  components/workflow/test/ai/miniforge/workflow/monitoring_test.clj`
- `bb test components/workflow`

## Deployment Plan

No deployment step. Internal workflow runtime cleanup only.

## Checklist

- [x] Removed duplicated ad hoc progress-monitor construction
- [x] Removed duplicated ad hoc supervision halt payload maps
- [x] Added direct tests for the new factories
