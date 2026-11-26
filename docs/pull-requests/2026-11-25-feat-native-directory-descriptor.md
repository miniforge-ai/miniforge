<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: native directory descriptor identity

## Overview

Provide an owned directory descriptor whose native identity remains available
without re-reading a replaceable pathname.

## Layer and dependencies

Infrastructure foundation, based on main. PR #2555 consumes this prerequisite
to bind qualification to the descriptor actually held rather than a path that
can change during acquisition. No live infrastructure actions.

## Motivation

Qualification must reject an A→B→A path swap that pins B while advertising A.
A witnessed directory stream retains an inode but does not identify it.

## Changes in detail

A fixed read-only system-Perl program opens a directory without following its
final symlink, reports device/inode from the open descriptor, and retains it
until the owning host releases the pin or closes its input pipe. The host
bounds receipt acquisition and shutdown, rejects malformed output and dead
helpers, and clears the helper environment. Missing capabilities fail closed.
The existing witnessed-stream API is unchanged; it is not descriptor identity.

Native compilation alone does not expose arbitrary POSIX calls. Java 21 on
this Mac returns an ordinary directory stream. Stock Babashka 1.12.217 returns
a secure stream but exposes it only as Closeable, without its attribute-view
operation. The system-Perl bridge avoids a custom native executable/toolchain.

## PR sequence

1. This independently tested descriptor lease and fixed native protocol (M).
2. #2555 consumes descriptor identity and adds real-pin ABA refusal (S).

Protocol decoding is pure; process ownership and cleanup stay at the native
boundary. No pathname re-stat is accepted as descriptor evidence.

## Testing plan

The native packet passes 56 tests / 281 assertions in each Java 21 product,
source Babashka and a freshly packaged CLI. New coverage is 7 tests / 32
assertions: strict receipt parsing, original identity across path replacement,
release, host EOF, helper death, malformed/silent helpers and file/symlink
refusal. A deliberate wrong-identity control produces 15 failures / zero
errors on both Java and Babashka. Seven exact namespace examples pass on both.

## Deployment plan

Qualified descriptor pins require system Perl and its core Fcntl module.
Unavailable capabilities refuse admission. There is no PATH lookup, downloaded
helper, shell, custom native image, or new live-infrastructure authority.

## Related issues/PRs

Prerequisite for #2555's descriptor-identity finding; #2559 supplies bounded
native process-death confirmation. No dependency on an unmerged PR.

## Checklist

- [x] Native JVM/Babashka descriptor identity, replacement, death and release.
- [x] Bounded malformed/absent receipt and cleanup tests.
- [ ] Owner post-hook standards and adversarial pass; normal signed commits.
- [ ] Current-head CI, automatic review and one settled full review.
