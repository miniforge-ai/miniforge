<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(self-healing): remove unused fetch-workaround-from-github stub

## Overview

Delete `fetch-workaround-from-github` from
`self_healing/workaround_detector.clj`. The function took two arguments,
ignored both and returned `nil`.

## Motivation

The function was a placeholder for a GitHub issue search that was never
written. It had no callers. A public function that always returns `nil`
reads as a working integration. Rule 008 (`no-dead-code`) says to delete
code like this.

## Changes in detail

- Remove the one function and its `TODO` comment, 18 lines in all.
- No other file changes. The self-healing interface never exported the
  function.

## Testing plan

No test changes. This is a pure deletion of a function with no callers.
A search of `components`, `bases`, `projects`, `development` and `tasks`
finds no reference to the name after the change. The existing
`workaround-detector-test` namespace still loads and passes.

## Deployment plan

No configuration or data change.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] No callers remain
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
