<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): give each resume-record write a unique temp file

## Overview

`resume-records/write-edn!` staged every write of a record in one shared
file, `<record>.tmp`, then renamed it over the record. Each write now
stages in its own file, `<record>.<uuid>.tmp`, and removes that file if
the write fails.

## Motivation

Two processes can write the same record. A launcher records a launch
under its lineage root while a restarted process settles the earlier
launch under the same root. With one shared temp file, the two writes
could mix their bytes. One rename could also take the file away before
the other ran. The second rename then threw `NoSuchFileException` and
that update was lost.

## Changes in detail

- `write-edn!` names its temp file with a random UUID, so no two writes
  share one.
- `write-edn!` deletes its temp file in a `finally`. A unique name is
  never reused, so a failed write would otherwise leave the file behind
  for good.
- The temp name still ends in `.tmp`. `pending-launches` lists only
  names ending in `.edn`, so it never reads a temp file as a record.

## Testing plan

- `write-edn-concurrent-writers-test` races 16 writers on one record. It
  checks that no writer fails, the record parses, and the directory
  holds only the record.
- `write-edn-failed-write-removes-its-temp-file-test` makes the rename
  fail by putting a non-empty directory at the record's path. It checks
  that the write throws and leaves no temp file.
- Both tests fail against the previous `write-edn!` and pass with this
  change. Run locally with
  `clojure -M:dev:test` on `ai.miniforge.cli.workflow-runner.resume-records-test`.
- Three assertions in `a-live-target-test` fail on this machine. They
  fail the same way on `main` and pass in CI. This change does not touch
  that path.

## Deployment plan

No configuration or data change. Records keep their names and content.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] Regression tests fail before the fix and pass after it
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
