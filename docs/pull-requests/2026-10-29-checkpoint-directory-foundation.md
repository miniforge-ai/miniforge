<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Validate checkpoint directory trust before resolving aliases

## Scope

Foundation for the approved protected-directory policy in #2094.
Provide private creation, protected canonical ancestry, and validation of the
original path and symlink owners before resolving aliases. Preserve trusted
aliases and existing read-only sharing. Reject unsafe existing directories
without changing permissions. Return canonical directories on native providers.

The immediate consumer is atomic checkpoint and pin publication in #2094;
this PR does not yet change the store's publication path. POSIX checks cover
ownership and mode bits; deployments must also protect directory ACLs.

## Verification

Regression tests cover private creation, read-only sharing, writable leaves,
trusted and foreign aliases, unsafe original ancestry, and native path resolution.
The focused JVM suite passes eight tests and 19 assertions.
Run normal hooks, standards, fresh-head review, and CI before merging.
