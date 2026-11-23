<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: observe supported local storage backends

## Overview

Read storage identity and backend facts for the approved local-POSIX support boundary.
These observations are not qualification or authority to execute effects.

## Motivation

Separate read-only backend policy from runtime lock proof and opaque root binding.
This keeps the qualification chain reviewable while its standards fixes and hosted
verification remain within the ordinary PR budget.

## Layer

Foundation policy and native read-only adapters in file-durability.
Base branch: `main`. PR #2533 is merged. The prepared qualification branch
consumes these observations immediately; durable run-fence adoption follows it.

## Changes in detail

Observe only unambiguous writable single-device XFS mounts on recognized local AHCI or
PCIe-NVMe stacks, or writable APFS with volume ownership and internal Apple Fabric backing.
Unknown, network, volatile, ambiguous or known unsafe stores refuse.
Keep root device/inode identity separate from backend-specific observations.
There are no writes, grants, deployments or live infrastructure changes.
Linux requires exact no-virtualization evidence from the installed native
`systemd-detect-virt`, plus allowlisted driver ancestry and local NVMe transport
where applicable. Missing helpers, VM/container reports, unknown drivers and
non-local transports refuse. These remain trusted host/runtime observations,
not hardware attestation or a power-loss guarantee.
Reject XFS external journal (`logdev`) and realtime (`rtdev`) options in either
mount-option field. Ext4 is deferred: its external journal can be selected from
the superblock without its mount table advertising a separate device. Until
positive journal-topology evidence exists, an ext4 mount cannot qualify through
this single-device profile. Existing non-durable workflows are unchanged.

## Testing plan

Use producer-shaped mount and disk-metadata fixtures. Pin rejection of unsupported
mounts, ambiguous tables, virtual or unmapped devices, mismatched root devices,
disabled APFS ownership and invalid UUID types. Mock only native I/O boundaries.
The packet, including the real macOS ACL fixtures, passes 21 tests and 122 assertions
in each JVM product, Babashka source and a fresh package.
Disabling the filesystem allowlist, device checks or ownership
check fails six, two and two assertions respectively on JVM and Babashka, with no
runtime errors. Ignoring the selected native exit protocol fails two further assertions.
Removing the separate-device guard fails six further assertions on each runtime,
covering both option fields and combined external-device layouts, with no errors.
The realistic PCI/virtio regression mirrors [Oracle's sysfs example](https://docs.oracle.com/en/operating-systems/oracle-linux/8/udev/udev-QueryingUdevandSysfs_view_attributes_for_a_device_and_its_parent_devices.html).
Controller transport follows the [kernel NVMe ABI](https://www.kernel.org/doc/html/latest/admin-guide/abi-stable.html).
Host detection follows the [native tool contract](https://www.freedesktop.org/software/systemd/man/latest/systemd-detect-virt.html).
Separate XFS devices follow the [kernel filesystem contract](https://github.com/torvalds/linux/blob/master/Documentation/admin-guide/xfs.rst).
Ext4 remains deferred after tracing external-journal selection and mount reporting
in [the kernel implementation](https://github.com/torvalds/linux/blob/master/fs/ext4/super.c).
Read-only local metadata checks confirm the macOS fixture shape;
the optional physical-classification field is absent even on this host's backing
partition. Strict lint and actual dependency-layer checks pass.
All nine exact namespace REPL examples pass on JVM and native Babashka.
The macOS ACL missing-path
interceptor calls the captured three-argument probe directly, so redefining the
public probe cannot intercept its own default-arity delegation. Production ACL
validation remains unchanged; the dedicated hosted Darwin gate stays required.

## Deployment plan

No deployment. These internal observations do not establish persistence, forced
publication, cross-runtime locks or a qualified authority root. The next layer
must establish those separately under the approved host-storage contract.

## Related issues/PRs

PR #2530 consumes the qualification chain. PR #2528 owns full stop.

## Checklist

- [x] Owner adversarial and applicable standards pass
- [x] Runtime packet and normal commit gates
- [ ] Current-head CI, resolved review cycle and signed conflict-free merge
