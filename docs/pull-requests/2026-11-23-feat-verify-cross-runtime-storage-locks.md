<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: verify cross-runtime storage locks

## Overview

Isolate the native JVM/Babashka mutual-lock proof needed by storage qualification
in PR #2555. This infrastructure PR is based on main and needs no unmerged code.

## Motivation

Qualification must retain a directory pin throughout its evidence and handle
lifetime. Separating the lower-level peer proof keeps that correction reviewable
without a size exception. Timeout, polling and shutdown bounds belong to trusted
host configuration, not embedded operational constants.

## Changes in detail

Verify publication and actual mutual file-lock exclusion in both directions on
one private disposable inode. Require a separately installed opposite runtime
and an absolute classpath. Pass validated finite bounds to both processes;
refuse premature exit, timeout or false lock claims and clean owned resources.
The host uses one monotonic deadline across all proof marker and exit waits. The peer
starts its own bounded clock rather than assuming process clocks share an epoch.
Expired markers cannot count as proof. Cancellation is restored before cleanup
and remains set when the call returns.
Merged PR #2560 supplies bounded native exit acknowledgement. Cleanup receives
its own configured exit budget after the handshake, confirms native death before
scratch deletion, and retains private scratch with an explicit unavailable anomaly
if death cannot be confirmed. This refusal also propagates after interruption;
it never becomes successful proof. Tests observe death at deletion and return,
without a later grace wait. Independent fixture teardown waits only after those
assertions and cannot turn a failed readiness assertion green.
Bounds govern cooperative marker and exit waits; native filesystem and process
launch calls are not preempted by those clocks.

## Testing plan

Use actual native processes and OS locks, including denial paths. Substitute
only peer launch for deliberately faulty disposable peers. Check both Java 21
products, source Babashka and a fresh package; prove negative controls and safe
REPL examples. Include the native proof in the Darwin CI packet.

Completed validation after composing merged PRs #2557 and #2560:

- The combined native packet passes 43 tests / 210 assertions in each of both
  Java 21 products, source Babashka and a fresh class-stripped CLI package.
  This includes the existing pin and cleanup suites; the peer packet is 24 / 116.
- Seven independent negative controls fail with zero errors on Java 21 and
  Babashka: mutual exclusion (1), owned-peer termination (4), marker expiry (1),
  runtime identity (1), shared host deadline (1), cancellation restoration (1),
  and the explicit unresolved-termination diagnostic (4).
- Fourteen exact safe examples from thirteen namespaces pass on both runtimes.
- The task classpath regression passes 1 test / 1 assertion. The native peer
  subset also passes the real project integration alias, without extra paths.

The Java 21 executable is selected explicitly for both JVM hosts and Babashka's
JVM peer. No live provider was invoked.

## Deployment plan

No provider actions or infrastructure changes. Trusted callers supply bounds
from their deployment configuration. The proof does not qualify backend
persistence or authorize effects; root qualification and retained identity remain
in PR #2555.

## Related issues/PRs

Merged PR #2533 supplies protected directories and publication checks. The pin
prerequisite PR #2557 is independent; both precede qualification in PR #2555.
Merged PR #2560 supplies acknowledged native process cleanup.

## Checklist

- [x] Implementation, native proof and owner standards/adversarial review.
- [ ] Current-head CI, automatic and full reviews, all threads resolved.
