<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Own temporary checkpoint files per write

## Scope and motivation

Give each checkpoint write a fresh same-directory temporary file.
Clean up its temporary file after success or failure.
Shared temporary names let overlapping writes consume or overwrite another writer's bytes.

## Layer and dependencies

Storage boundary, small follow-up to #2039. Based on its reviewed branch;
wait for #2039 to merge before opening this dependent PR.
Preserve all previous branches and normal signing, test, review, and CI gates.

## Verification plan

Use deterministic interleaving and failed-write tests, not sleeps.
Run affected consumers serially and test the rebuilt CLI outside the source tree.
Verify cleanup does not replace the original failure or delete the published target.

## Local verification

The prior CLI fails four assertions in the new deterministic regression tests.
All three workflow consumers pass serially (2m04s). The rebuilt CLI is
39,233,073 bytes; isolated checkpoint suites pass 14 tests and 57 assertions.
No production sources overlay the package. New JVM tests take 12 ms and 6 ms,
so both remain in the workflow brick.

The adversarial pass traced relative-path resolution, temporary creation, write,
rename, and cleanup. Temporary files use the platform's fresh-file permissions.
The shared cleanup boundary preserves original failures and fatal priority.
Namespaces have at most three inferred strata; no duplicated cleanup policy.

## Limits and deployment

No format migration. This is per-file temporary ownership, not a transaction
across snapshots, phase results, and manifests. Force ordering and structured
failure propagation remain separate work.
