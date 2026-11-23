<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: confirm native probe cleanup

## Overview

Own a disposable probe directory and its native process through acknowledged
termination. This infrastructure prerequisite fixes the cleanup finding in
PR #2559 without a size exception. It is based on main.

## Motivation

Requesting forced termination does not establish that a process has exited.
Deleting its directory first can race further writes. A failed termination wait
must refuse cleanup and retain the private directory with explicit diagnostics.

## Changes in detail

Wait within one caller-supplied monotonic shutdown budget, retaining interruption
without resetting that budget. Confirm native process death before deleting its
scratch directory. Reuse the resource-cleanup failure policy so cleanup runs on
operation failure without masking fatal or cancellation causes.

The native descendant snapshot is not containment of a hostile forking workload.
Native process enumeration and signalling are not preempted by the wait budget.
If cleanup fails alongside an operation error, the existing failure policy retains
the independent cleanup failure as suppressed rather than replacing the primary.
An unresolved cleanup never acknowledges native death or deletes scratch.

The dependency DAG is probe-resources → native-process-exit → native-exit-wait;
ownership policy also imports the existing resource-cleanup interface.
No domain or other component imports a new internal namespace.

## Testing plan

Use disposable native processes and directories, including missed termination,
interruption and operation failure. Observe process death at directory deletion,
not after a test-side grace period. Verify both Java 21 products, source Babashka
and a fresh package, with independent fault controls and safe examples.

The actual Darwin task passed 28 tests / 113 assertions in each of both Java 21
products, source Babashka and a freshly built package. The new cleanup packet is
7 tests / 60 assertions per runtime. Native failures are observed at deletion and
return without a later grace period. Five independent fault controls fail with
4 / 5 / 1 / 1 / 2 assertions and zero errors in both Java 21 and Babashka:
false death acknowledgement, ignored unresolved termination, discarded interruption,
renewed retry deadline, and masked fatal operation respectively.

## Deployment plan

No provider or infrastructure actions. A retained directory on unresolved cleanup
is not an acknowledgement of a stopped process. Storage qualification and lock
proof admission remain separate; this primitive adds no effect authority.

## Related issues/PRs

PR #2559 consumes this prerequisite before its cleanup finding can be resolved.
Merged PR #2557 supplies the existing private-directory infrastructure.

## Checklist

- [ ] Implementation, native proof and owner standards/adversarial review.
- [ ] Current-head CI, automatic and full reviews, all threads resolved.
