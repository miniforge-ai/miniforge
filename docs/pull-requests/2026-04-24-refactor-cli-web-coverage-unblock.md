<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: unblock CLI web coverage instrumentation

## Overview
Extracts the CLI dashboard status/workflow rendering path into a smaller
namespace so Cloverage can instrument the web surface without tripping the
JVM method-size limit.

## Motivation
`bb ccov` was failing before tests even ran because
`ai.miniforge.cli.web.components/workflow-status` compiled into a method
that was too large for Cloverage instrumentation. That made the coverage
task unreliable for the whole repo.

## Base Branch
`main`

## Changes In Detail
- adds `bases/cli/src/ai/miniforge/cli/web/components/status.clj`
  - moves `status-indicator`, `workflow-status-icon`, and
    `workflow-status` into a smaller focused namespace
  - keeps the rendering path localized through the existing CLI message
    catalog
- updates `bases/cli/src/ai/miniforge/cli/web/components.clj`
  - reduces the size of the original namespace
  - keeps the existing public vars as pass-throughs so callers do not
    change
- updates `bases/cli/test/ai/miniforge/cli/web/components_test.clj`
  - adds direct coverage for the extracted workflow-status surface

## Testing Plan
Executed in `/Users/chris/ws/miniforge.ai/thesium-career/miniforge`:
- `git diff --check`
- `clojure -Sdeps '{:paths ["bases/cli/src" "bases/cli/resources" "bases/cli/test"]}' -M:test -e "(require 'ai.miniforge.cli.web.components-test) (let [result (clojure.test/run-tests 'ai.miniforge.cli.web.components-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
- `bb ccov`

Results:
- `components-test` passed: 5 tests, 17 assertions
- `bb ccov` no longer fails on `Method code too large!` for
  `ai.miniforge.cli.web.components`
- `bb ccov` now completes instrumentation and writes a coverage report
  with aggregate coverage:
  - `% forms`: `52.61`
  - `% lines`: `68.62`
- the full `bb ccov` command still exits non-zero (`43`) because of
  existing downstream failures in the broader suite; those are outside
  this render-namespace unblock slice

## Deployment Plan
No migration is required.

Consumers pick up the smaller CLI web rendering surface with the updated
checkout.

## Checklist
- [x] Removed the CLI web method-size coverage blocker
- [x] Preserved the existing public component API
- [x] Added direct test coverage for the extracted status surface
- [x] Verified full coverage instrumentation reaches report generation
