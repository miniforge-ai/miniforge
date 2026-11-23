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

Observe only unambiguous writable ext4/XFS mounts on non-virtual Linux block
devices, or writable APFS with volume ownership and internal Apple Fabric backing.
Unknown, network, volatile, ambiguous or known unsafe stores refuse.
Keep root device/inode identity separate from backend-specific observations.
There are no writes, grants, deployments or live infrastructure changes.

## Testing plan

Use producer-shaped mount and disk-metadata fixtures. Pin rejection of unsupported
mounts, ambiguous tables and disabled APFS ownership. Run the affected packet in
both JVM products, Babashka source and a fresh package before publishing.

## Deployment plan

No deployment. These internal observations do not establish persistence, forced
publication, cross-runtime locks or a qualified authority root. The next layer
must establish those separately under the approved host-storage contract.

## Related issues/PRs

PR #2530 consumes the qualification chain. PR #2528 owns full stop.

## Checklist

- [ ] Owner adversarial and applicable standards pass
- [ ] Runtime packet and normal commit gates
- [ ] Current-head CI, resolved review cycle and signed conflict-free merge
