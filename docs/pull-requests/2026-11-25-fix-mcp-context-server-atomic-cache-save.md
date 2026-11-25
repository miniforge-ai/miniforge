<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(mcp-context-server): save the persistent cache atomically

## Overview

`save-cache!` now writes `.miniforge/context-cache.edn` to a sibling temp file
and renames it into place.

## Motivation

The cache is shared across phases through the worktree. A bare `spit` that is
interrupted, or that races a concurrent phase server, leaves truncated EDN.
`read-cache-edn` then returns nil and the next phase silently loses its
pre-populated context.

## Testing

`save-cache-leaves-no-temp-file-test` saves twice and asserts only the cache
file remains. Not run locally: `bb` and `clojure` were unavailable in the
sweep environment, so CI must confirm.
