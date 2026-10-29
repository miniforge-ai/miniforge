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
Validate every intermediate symlink target, including relative chains, with a
40-hop expansion bound. Trusted aliases into external sticky ancestors remain
supported when those ancestors are not the checkpoint directory itself.
Provide a no-follow append boundary that creates private POSIX history files
while preserving existing file permissions and append semantics.
Refuse existing nonregular history entries before opening them.

The immediate consumer is atomic checkpoint and pin publication in #2094;
this PR does not yet change the store's publication path. POSIX checks cover
ownership and mode bits; deployments must also protect directory ACLs.

## Verification

Regression tests cover private creation, read-only sharing, writable leaves,
trusted and foreign aliases, unsafe original ancestry, and native path resolution.
History regressions cover private creation, retained sharing, append order,
and refusal to follow a symlink.
Root-identity and chained-alias regressions fail on the earlier publication head.
The focused JVM suite passes 17 tests and 40 assertions, including chained
aliases through sticky external ancestors for both creation and existing paths.
Run normal hooks, standards, fresh-head review, and CI before merging.
