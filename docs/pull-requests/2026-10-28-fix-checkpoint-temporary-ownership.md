<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Own temporary checkpoint files per write

## Scope and motivation

Give each checkpoint write a fresh same-directory temporary file.
Clean up its temporary file after success or failure.
Shared temporary names let overlapping writes consume or overwrite another writer's bytes.

## Layer and dependencies

Storage boundary, small follow-up to merged #2039. Aligned with main after its merge.
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
rename, and cleanup. POSIX temporary files are explicitly private (`0600`).
The shared cleanup boundary preserves original failures and fatal priority.
Namespaces have at most three inferred strata; no duplicated cleanup policy.
Normal signed hooks pass; the standards scan reports no violations.
The initial change had 91 reportable code lines. Parent alignment preserves the
exact tested production and test tree.

## Permission review correction

The approved policy makes first POSIX publications private (`0600`).
Replacements retain the existing POSIX read, write, and execute permission bits.
Permissions transfer only after content writing completes, before atomic rename.
Permission failures abort publication and clean up the owned temporary file.
Non-POSIX providers retain native defaults; ACL and ownership preservation are not promised.
Two regression tests cover seven assertions, including private incomplete files.
The previous package fails three assertions; the corrected source passes all seven.
All three workflow consumers pass (1m33s); the rebuilt CLI is 39,233,889 bytes.
Four isolated packaged checkpoint suites pass 15 tests and 59 assertions.
New JVM tests take 11 ms and 3 ms; the standards scan reports zero violations.
The correction has 68 reportable lines; the complete PR has 157.

## Limits and deployment

The second review identified symlink-mode copying and an earlier failure point
in the rename regression. Symlink replacements now publish privately without
changing the linked file. File type and permissions come from one no-follow read.
The rename test deletes the temporary only after successful permission preparation.
The previous package fails the new symlink assertion. All three consumers pass
in 1m40s; isolated packaged suites pass 16 tests and 64 assertions.
Affected JVM tests take 4–36 ms, remaining within the brick test budget.

No format migration. This is per-file temporary ownership, not a transaction
across snapshots, phase results, and manifests. Force ordering and structured
failure propagation remain separate work.
