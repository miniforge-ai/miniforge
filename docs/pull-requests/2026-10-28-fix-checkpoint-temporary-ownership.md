<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Own temporary checkpoint files in protected directories

## Problem and behavior

Uses the protected path and history boundaries merged in #2244.

Shared temporary filenames let overlapping checkpoint writes consume another writer's bytes.
Each write now owns a unique same-directory temporary and cleans it after success or failure.
Atomic replacement preserves the previous checkpoint when publication fails.

New POSIX checkpoint files are private (`0600`).
Replacements preserve existing POSIX read, write, and execute bits.
Replacing a symlink publishes a private file and leaves the linked file untouched.
Gate-history append creates private files, preserves existing permissions, and
never follows a final symlink.

## Directory policy

New checkpoint and lock directories are private (`0700`).
Existing directory permissions are preserved, including read-only sharing.
Resolved ancestry must have trusted owners and prevent entry replacement by other accounts.
Sticky ancestors such as `/tmp` remain supported; writable checkpoint directories are refused.
Publication uses the resolved directory even if a caller's directory alias changes during writing.
Returned publication paths identify the files written.
The store validates its root and run directory before any phase or history effects.
Pin markers use the same writer with existing-only directory preparation.
Pinning never recreates a run removed by GC; marker temporary names retain their GC meaning.

This policy checks POSIX ownership and permission bits.
Deployments must also protect directory ACLs; ownership and ACL preservation are not promised.
Non-POSIX providers retain native defaults.

## Verification

The previous package fails the ownership, permission, and protected-directory regressions.
The protected-path prerequisite suites pass 21 tests and 47 assertions on JVM
and Babashka. Affected workflow consumers pass in 2m35s after updating path
expectations to the resolved publication contract.
The CLI rebuilt after actual-main alignment passes 66 tests and 191 assertions
with no production source overlays. The pin/GC project test passes separately
on its JVM runtime: one test and seven assertions.
The actual rename-failure test verifies preparation completed before removing the temporary.
The pin-publication test observes actual lock contention before releasing the
pin and proves GC retains the run. Its release happens in a finally block.
The integration runner discovers that project test automatically.

The adversarial pass traces first creation, replacement, aliases, unsafe ancestry, and cleanup.
The shared cleanup boundary preserves original failures.
The standards scan against the merged prose prerequisite reports zero violations.
Normal hooks, signing, standards, review, and CI remain required.

## Limits

No checkpoint format migration.
This change provides per-file publication, not a transaction across snapshot, phase, and manifest files.
Force ordering remains a separate follow-up.
