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
The host uses one monotonic deadline across all marker and exit waits. The peer
starts its own bounded clock rather than assuming process clocks share an epoch.
Expired markers cannot count as proof. Cancellation is restored before cleanup
and remains set when the call returns.

## Testing plan

Use actual native processes and OS locks, including denial paths. Substitute
only peer launch for deliberately faulty disposable peers. Check both Java 21
products, source Babashka and a fresh package; prove negative controls and safe
REPL examples. Include the native proof in the Darwin CI packet.

## Deployment plan

No provider actions or infrastructure changes. Trusted callers supply bounds
from their deployment configuration. The proof does not qualify backend
persistence or authorize effects; root qualification and retained identity remain
in PR #2555.

## Related issues/PRs

Merged PR #2533 supplies protected directories and publication checks. The pin
prerequisite PR #2557 is independent; both precede qualification in PR #2555.

## Checklist

- [ ] Implementation, native proof and owner standards/adversarial review.
- [ ] Current-head CI, automatic and full reviews, all threads resolved.
