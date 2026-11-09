<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: cap processes per task capsule with --pids-limit

## Overview

Every task capsule the OCI-CLI executor creates now gets `--pids-limit 2048`.
The value lives in the runtime registry's `:defaults` beside `:memory` and
`:cpu`, and a caller's `:resources` can replace it.

## Motivation

The executor already caps memory and CPU, but not the number of processes
and threads. Docker sets no process limit of its own, so a fork bomb in a
capsule, whether from an agent or from code under test, could use up the
host's process table. On macOS that is the Docker or Podman VM, which every
other running capsule shares.

2048 is the limit Podman ships with (`pids_limit` in
`/usr/share/containers/containers.conf` on Podman 5.8.2). Governed runs under
Podman, the default OSS runtime, already work within it, so Podman behavior
does not change and Docker gets the same bound.

## Changes in Detail

1. `runtime/registry.edn`: `:pids-limit 2048` in the Docker and Podman
   `:defaults`, documented in the header comment.
2. `oci-cli.container`: `runtime-default-resources` reads `:pids-limit`, and
   `build-resource-args` emits `--pids-limit <n>` when it is set.
3. N11-delta §4: the `:resource-limits` capability now names process-count
   limits.

## Testing Plan

1. `registry-test`: Docker and Podman report a `:pids-limit` default of 2048.
2. `oci-cli-test/create-container-pids-limit-test`: create-container emits
   the registry default, and a caller's `:pids-limit` replaces it as one
   argument.
3. By hand on rootless Podman 5.8.2: a container started with the full
   hardening flag set plus `--pids-limit 2048` starts and reads 2048 from
   `/sys/fs/cgroup/pids.max`. With `--pids-limit 32`, a loop that starts 100
   background processes fails with `can't fork`.

## Deployment Plan

None. The flag applies to containers created after the change.

## Known gaps

The runtime registry has no Malli schema, which config-as-data (rule 007)
asks every EDN config to have. That predates this change and is left for its
own PR.

## Related Issues/PRs

From a review of PAODO-Workspaces, which caps each workspace container's
process count.

## Checklist

- [x] Registry default and container flag
- [x] Tests for the default and the override
- [x] Spec capability text updated
