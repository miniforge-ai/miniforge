<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(connector-file): write output files atomically

## Overview

`connector-file/writer` wrote JSON and EDN output in place. Both writers
now write a sibling temp file and rename it over the target.

## Motivation

In `:append` mode the writer reads the existing records, then truncates
the file and rewrites it. A crash, a kill or an encode error during the
rewrite left a truncated file. Records written by earlier runs were
lost.

## Changes in detail

- `write-atomically!` writes `.<uuid>.tmp` beside the target (the name
  does not embed the target's, so a long output name stays within the
  filesystem's component limit),
  moves it over the target with `ATOMIC_MOVE`, and deletes the temp
  file in a `finally`.
- `create-temp-like!` checks the target's `FileStore` for POSIX support
  and gives the temp file the target's POSIX
  permissions before any record is written. Replacing an owner-only
  file does not loosen it, and the records are never readable more
  widely while they are written.
- An existing symlink is resolved first. The write updates the file the
  link points at and keeps the link.
- A link whose target does not exist is replaced by a regular file. The
  in-place writer created the missing target instead.

## Testing plan

`writer_test.clj` adds three tests:

- A JSON write that fails during encoding leaves the earlier records
  intact and leaves no temp file. This test fails against the in-place
  writer.
- Appending to an owner-only file keeps its permissions.
- Appending through a symlink updates the linked file and keeps the
  link.

The permission and symlink tests run only on a POSIX filesystem. The
writer and interface namespaces pass with no failures.

## Deployment plan

No configuration or data change. Output files keep their names and
formats.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] Regression test fails before the fix and passes after it
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
