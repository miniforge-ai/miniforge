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

Foundation layer in file-durability; publication and ACL probes are in
merged PRs #2532 and #2533; read-only backend observations are separated into
merged PR #2552. This branch is based on main with those prerequisites included.
The internal API (surface class 1) requires a supported profile, an existing
canonical protected root, and the other runtime's absolute launcher and classpath.
There is no fallback based on POSIX permissions alone.
Qualification requires explicit finite pin-enumeration, acquisition and peer-lock
limits, in separate pin and lock sections of the host configuration.
After a read-only storage preflight, it captures native identity before acquiring
the directory pin and compares that identity with the authoritative observation
under the pin. The resulting opaque handle owns the pin for its entire lifetime.
The host must explicitly release it after all consumers finish; release is
idempotent, returns a specific acknowledgement, and permanently refuses further
resolution before filesystem observation. A closed handle cannot revive when
device/inode numbers are later reused.
Handle-type validation belongs to the public interface and a named schema;
the implementation trusts those inputs while checking current native liveness.

The initial Linux profile accepts an unambiguous writable single-device XFS mount,
recognized AHCI or PCIe-NVMe driver ancestry, exact no-virtualization evidence from
the installed native detector, and no known unsafe flags or separate journal/realtime
devices. Missing or unknown evidence refuses. Ext4 remains deferred until positive
journal-topology evidence exists. Mount observation must match the kernel device
identity and visible parent chain; hidden and stacked mounts refuse.
macOS requires writable APFS, enabled volume
ownership, verified non-granting ACLs, and internal Apple Fabric backing.
Unknown or unsupported topology refuses. The host must configure storage
retained across restarts; workloads must not control the volume or its ownership.

Both runtimes must verify forced publication on the root. The probe verifies
mutual lock exclusion in both directions, then reacquires the lock after clean
peer exit. The opaque handle binds to one root and its device and inode.
Resolving the handle and attaching a direct child recheck macOS ACLs.
The kernel adoption is separate. No broker or effect authority is added.

## Testing plan

Cover unknown and mismatched profiles, temporary and remote mount types,
ambiguous mount data, no peer proof, and root replacement. Test actual
JVM/Babashka peers and forced publication on disposable files. Bound native
storage metadata to a shared process/read deadline and a fixed output limit.

The prepared composition with the peer's I/O-cleanup fix passes 67 tests /
317 assertions in each Java 21 product, source Babashka and a fresh package.
The exact project integration alias passes 21 tests / 75 assertions. The same
native packet is wired into the hosted Darwin gate. Local positive checks
qualify and resolve a disposable APFS root, attach a protected child and release
the owned handle, with four successful assertions in each runtime mode.
These are runtime/storage-contract checks, not power-loss certification or
proof that an unsupported hosted VM qualifies as persistent authority storage.

Six lifetime fault controls produce 2 / 2 / 1 / 1 / 1 / 2 expected failures and
zero errors on Java 21 and Babashka. They cover before-pin identity binding,
failed-proof resource transfer, late pin closure, closed-before-I/O refusal,
current identity revalidation and native release. Qualification tests use real native
directory descriptors; their fixture independently releases every acquired pin,
including unexpected successes under fault injection. Root replacement is
checked both during qualification and throughout handle ownership.
The interface-admission regression catches misplaced validation with 13 failures
and zero errors before the fix. Bypassing the corrected admission produces the
same 13 expected failures and zero errors on Java 21 and Babashka.
Seven exact qualification namespace examples pass in both runtimes.

Merged PR #2563 supplies the shared resource-transfer failure-priority fix;
the composed packet retains its fatal/interruption and cleanup-provenance
regressions. Repeat proof if dependency resolution changes source. No current-head
review or merge gate is waived by these local results.

## Deployment plan

Host-only opt-in with trusted, installed launchers and a persistent root.
Requalify after a controller restart. No cluster access, runtime shutdown,
experiments or credential actions.
The dependency chain #2560 → #2559 → this PR is merged through the peer proof,
with merged foundations #2562 and #2563. This branch is composed with main;
the peer squash has the exact same tree as its locally validated input.
Every current-head CI and review gate still applies to this PR. Its explicit
lock-limit contract replaces the old timing constants; no compatibility arity
or implicit bounds remain. New shared numeric schemas keep count bounds
distinct from time units without duplicating their native integer constraint.

## Related issues/PRs

PR #2530 consumes this prerequisite. PR #2528 owns the full-stop contract.

## Checklist

- [x] Final composed implementation and adversarial standards pass complete.
- [x] Final serial native qualification and packaged proofs complete.
- [ ] Current-head CI and full review settled.
