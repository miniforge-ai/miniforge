<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: qualify persistent local authority storage

## Overview

Qualify the local filesystem/runtime backend before binding a protected authority root.
POSIX permission support alone does not establish persistence or process-lock behavior.

## Approval record

On 2026-11-15 the operator approved a qualified persistent local-POSIX support
boundary, with fsync/atomic-publication and JVM/Babashka lock checks. Unknown,
network and volatile stores remain unsupported. Unqualified storage blocks durable
admission/restart; separately authorized containment retains its narrow outage exception.
This is implementation authority, not permission for live infrastructure actions
or a power-loss guarantee beyond the storage/runtime contract.

## Motivation

PR #2530's remaining BLUE finding requests independent storage/runtime qualification.
The approved boundary needs implementation and evidence, not an approval bypass.

## Changes in detail

Foundation layer in file-durability; publication primitives are in #2532.
The internal API (surface class 1) requires a supported profile, an existing
protected root, and the other installed runtime's absolute launcher and classpath.
There is no fallback based on POSIX permissions alone.

Linux accepts an unambiguous writable ext4 or XFS mount with a non-virtual block
device and no known unsafe flags. macOS accepts writable APFS with an observed
internal Apple Fabric backing device. Unknown or unsupported topology refuses.
The host must configure storage retained across restarts.

Both runtimes must verify forced publication on the root. The probe verifies
mutual lock exclusion in both directions, then reacquires the lock after clean
peer exit. The opaque handle binds to one root and its device and inode.
The kernel adoption is separate. No broker or effect authority is added.

## Testing plan

Cover unknown and mismatched profiles, temporary and remote mount types,
ambiguous mount data, no peer proof, and root replacement. Test actual
JVM/Babashka peers and forced publication on disposable files. Bound native
storage metadata to a shared process/read deadline and a fixed output limit.

Local macOS proof passed in JVM, Babashka source, and fresh packaged Babashka.
Each run passed 12 tests and 28 assertions plus positive APFS qualification.
The Linux mount policy has fixture coverage. This is not power-loss certification.

## Deployment plan

Host-only opt-in with trusted, installed launchers and a persistent root.
Requalify after a controller restart. No cluster access, runtime shutdown,
experiments or credential actions.

## Related issues/PRs

PR #2530 consumes this prerequisite. PR #2528 owns the full-stop contract.

## Checklist

- [ ] Implementation and adversarial standards pass complete.
- [ ] Serial qualification and packaged proofs complete.
- [ ] Current-head CI and full review settled.
