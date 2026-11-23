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

The extended native packet passes 24 tests and 63 assertions in each JVM product,
Babashka source and a fresh package. It covers the opaque-root contract, actual
native ACL/metadata behavior, real JVM/Babashka mutual locks, and refusal to skip
a missing peer runtime or classpath. The same packet is wired into the existing
hosted Darwin gate. Local positive checks also qualified and resolved a disposable
APFS root and attached its protected child, with three successful assertions in
each mode. The Linux backend policy has producer-shaped fixture coverage in #2552.
These are runtime/storage-contract checks, not power-loss certification or proof
that an unsupported hosted VM qualifies as persistent authority storage.
Targeted controls that bypass peer proof, root identity revalidation, or mandatory
peer admission produce one, one and two assertion failures respectively, with no
runtime errors, in both JVM and native Babashka. Twelve exact qualification examples
pass in each. The tooling regression pins product-relative and absolute peer
classpath entries, including a path containing spaces.
The real peer-process lock scenario lives in project integration tests, outside
the fast component packet.
Root-qualification tests also live in project integration tests because they now
acquire native directory descriptors. Their lifetime fixture independently releases
every acquired pin, including an unexpected success under fault injection.
Denial scenarios retain the production coordinator and OS locks, replacing only
native peer launch with processes that exit prematurely, never acknowledge, or
claim a lock they do not hold. Each refuses qualification proof, terminates its
owned peer and removes its scratch directory. Bypassing exclusion produces one
assertion failure; disabling peer termination produces four, with zero runtime
errors on both JVM and Babashka. Fixtures independently clean their own processes
even when production cleanup is deliberately disabled by those controls.
Root-replacement coverage includes a swap inside the peer-proof callback, keeping
both displaced directories alive to prevent inode reuse. Qualification refuses
the new, unprobed root. Ignoring the original observation fails that assertion
on both runtimes, with zero errors.

The retained-pin packet passed 44 tests / 127 assertions in each Java 21 product,
source Babashka and a fresh package before the final read-only-preflight guard.
Six lifetime fault controls produce 2 / 2 / 1 / 1 / 1 / 2 expected failures with
zero errors in Java 21 and Babashka. They cover the before-pin identity binding,
failed-proof resource transfer, late pin closure, closed-before-I/O refusal,
current identity revalidation and actual native release.
The final composed packet must be rerun after the unsupported-store preflight
regression and after consuming the independently reviewed peer-lock foundation.
No current-head review or merge gate is waived by these local results.
The owner pass also reproduced a failure-priority defect in the adopted resource
transfer helper: failed close masked fatal operation errors and interruption
(three tests, six failures, zero runtime errors before the fix). It now uses
the existing resource-cleanup interface so fatal/cancellation causes survive
and independent close failures remain suppressed. The required dependency is
explicit in every affected product; final composed validation includes these cases.

## Deployment plan

Host-only opt-in with trusted, installed launchers and a persistent root.
Requalify after a controller restart. No cluster access, runtime shutdown,
experiments or credential actions.
The documented dependency chain is #2560 → #2559 → this PR. The local checkout
is composed with pending #2559 at 3bb50af64 while its CI/review cycle runs;
it will not be published until that prerequisite merges to main and the
main-based composed diff is within budget and passes every gate. Its explicit
lock-limit contract replaces the old timing constants; no compatibility arity
or implicit bounds remain. New shared numeric schemas keep count bounds
distinct from time units without duplicating their native integer constraint.

## Related issues/PRs

PR #2530 consumes this prerequisite. PR #2528 owns the full-stop contract.

## Checklist

- [ ] Final composed implementation and adversarial standards pass complete.
- [ ] Final serial native qualification and packaged proofs complete.
- [ ] Current-head CI and full review settled.
