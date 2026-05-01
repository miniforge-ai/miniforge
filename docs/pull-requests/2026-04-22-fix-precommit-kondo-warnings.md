<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix: clear persistent clj-kondo warnings from workflow execution

## Overview

Remove the persistent `clj-kondo` warnings from the pre-commit path by fixing
the namespace declarations in `workflow.execution`.

## Motivation

The pre-commit hook has been warning on every commit because
`components/workflow/src/ai/miniforge/workflow/execution.clj` used
`clojure.java.shell`, `clojure.string`, and `clojure.java.io` without requiring
them. That makes the hook noisy and easy to ignore, even though the warning is a
real source issue in the repo.

## Changes In Detail

- Add explicit requires for:
  - `clojure.java.io`
  - `clojure.java.shell`
  - `clojure.string`
- Switch the fully qualified calls in `merge-sub-worktree-changes!` to the new
  aliases so lint resolves them correctly
- Keep behavior unchanged; this is a source/lint cleanup only

## Testing Plan

- Run `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/execution.clj`
- Run `bb test components/workflow`

## Deployment Plan

No runtime deployment. Merge normally; this only removes persistent lint noise
from the commit path.

## Related Issues/PRs

- Follow-up hygiene fix after `#636`, which surfaced the warning during
  pre-commit validation

## Checklist

- [x] `workflow.execution` requires the namespaces it uses
- [x] `clj-kondo` warning is gone for the file
- [x] Workflow brick tests pass
