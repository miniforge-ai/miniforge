<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Config writer review follow-ups

## Overview and motivation

Four Copilot findings on the shared config writer (#2111), raised on #2113
after it was retargeted, against code that is now on main.

## Layer and dependencies

Foundation (`components/config`). Based on main.

## Changes

1. A config file holding the EDN form `nil` was read as empty and overwritten.
   `parse-config` now returns nil only for blank or comment-only text; a lone
   `nil` is `:invalid-input`, like any other non-map form.
2. The target was resolved (`exists?` then `real-path`) outside the writer's error handler.
   Deleting it between these calls made `update-config!` and `write-config!` throw instead of returning an anomaly
   naming the path.
   `call-with-lock` now resolves it inside its handler and passes it to the
   thunk.
3. The mode and symlink tests error on Windows, so they and the cross-process symlink lock test are skipped there.
   The not-synced test is also skipped because Windows skips directory fsync.
4. The interface promised that a failed write leaves the file unchanged, but a
   failed directory fsync is reported after the rename. That case now carries
   subtype `:anomalies.config/not-synced`, and both docstrings say the write
   landed and a non-idempotent update must not be retried on it.

## Verification plan

Regression tests fail on main for `parse-config` on `"nil"` and a lone nil in the refuse-to-overwrite cases.
A target resolution throwing `NoSuchFileException` must make both writers return a `:fault` naming the path.
A failed `confirm!` must return subtype `:anomalies.config/not-synced` with new content in place. Config, CLI, pr-sync
and dashboard tests pass on the JVM; the config
writer tests also pass under babashka.

## Scope and deployment

No format change. A config.edn that is just `nil` now makes writers refuse
instead of replacing it.
