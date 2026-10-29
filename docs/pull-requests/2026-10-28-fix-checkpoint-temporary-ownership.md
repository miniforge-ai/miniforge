<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Own temporary checkpoint files in protected directories

## Problem and behavior

Shared temporary filenames let overlapping checkpoint writes consume another writer's bytes.
Each write now owns a unique same-directory temporary and cleans it after success or failure.
Atomic replacement preserves the previous checkpoint when publication fails.

New POSIX checkpoint files are private (`0600`).
Replacements preserve existing POSIX read, write, and execute bits.
Replacing a symlink publishes a private file and leaves the linked file untouched.

## Directory policy

New checkpoint and lock directories are private (`0700`).
Existing directory permissions are preserved, including read-only sharing.
Resolved ancestry must have trusted owners and prevent entry replacement by other accounts.
Sticky ancestors such as `/tmp` remain supported; writable checkpoint directories are refused.
Publication uses the resolved directory even if a caller's directory alias changes during writing.
Pin markers use the same writer with existing-only directory preparation.
Pinning never recreates a run removed by GC; marker temporary names retain their GC meaning.

This policy checks POSIX ownership and permission bits.
Deployments must also protect directory ACLs; ownership and ACL preservation are not promised.
Non-POSIX providers retain native defaults.

## Verification

The previous package fails the ownership, permission, and protected-directory regressions.
The rebuilt CLI passes 43 isolated checkpoint, lock, pin, and GC tests with 144 assertions.
All three workflow consumers pass against current main in 2m12s with clean process shutdown.
Eight focused directory tests and the pin-publication integration pass 37 JVM assertions.
Directory tests take under 22 ms; the integration takes 15 ms in this run.
The actual rename-failure test verifies preparation completed before removing the temporary.
The pin-publication test uses latches to prove GC waits for the held lock and retains the run.
The integration runner discovers that project test automatically.

The adversarial pass traces first creation, replacement, aliases, unsafe ancestry, and cleanup.
The shared cleanup boundary preserves original failures.
The standards scan against the merged prose prerequisite reports zero violations.
Normal hooks, signing, standards, review, and CI remain required.

## Limits

No checkpoint format migration.
This change provides per-file publication, not a transaction across snapshot, phase, and manifest files.
Force ordering remains a separate follow-up.
