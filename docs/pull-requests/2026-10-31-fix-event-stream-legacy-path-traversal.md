<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(event-stream): legacy events-dir lookup uses the sanitized workflow-id segment

## Overview

`workflow-events-dir` looks for a run's events in three places:
`archived/`, `live/`, and the legacy flat layout directly under the
events directory. The first two built their path from the sanitized
workflow-id segment. The legacy probe used the raw id. It now uses the
same segment.

## Motivation

A raw id such as `../outside` made the legacy probe resolve to a
directory beside the events directory. If that directory existed, it was
returned as the run's events directory. Resume and event queries then
read from outside the events directory. Ids arrive inside ingested
events, so the id is untrusted input. The dashboard's ingest endpoint
requires a token, but a token holder still chooses the id. The path
must be safe whatever the id is.

A missing id had a related fault. `(str nil)` is the empty string, so
the legacy probe resolved to the events directory itself.

## Changes in detail

- `reader/workflow-events-dir` builds the legacy path from
  `naming/workflow-id-segment`, the function `sinks` already uses for
  the `archived/` and `live/` paths.
- Ids that are already safe names map to the same segment as before.
  UUIDs, plain names and keyword names are all safe names. Existing
  legacy directories still resolve.
- A legacy directory whose name is not a safe segment no longer
  resolves by its raw name. That is the intended effect of the fix.

## Testing plan

`live_retention_test.clj` adds three checks:

- A traversal id does not resolve to a directory beside the events
  directory.
- A missing id does not resolve to the events directory itself.
- A legacy flat run directory still resolves, by UUID and by string.

The first two fail against the previous code and pass with this change.
The namespace runs 13 tests and 39 assertions with no failures.

## Deployment plan

No configuration or data change.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] Regression tests fail before the fix and pass after it
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
