<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: pin protected directory identity

## Overview

Provide descriptor-held directory identity and protected direct-child binding
for the storage qualification in #2555.

## Motivation

Device and inode numbers alone are reusable after deletion. A qualification
handle must keep the original directory inode allocated for its lifetime.
The pending qualification PR needs this prerequisite without a size exception.

## Changes in detail

File-durability infrastructure, based on main. Restrict descriptor pinning to
the qualified native Unix directory-stream implementations. Bind the open
directory to a fresh private witness through its descriptor-backed iterator;
unknown implementations or a failed binding refuse. Keep the directory open
until the owning host calls `directory-lease/release!`. This is not storage
qualification by itself. The resource is an explicit lease, not a Java Closeable.

The existing protected-child checks are a separate directory identity primitive:
require a canonical protected direct child on the parent's device and verify
native access controls. Their consumers still must establish parent storage
qualification separately.

The JVM-only secure attribute-view API is not exposed in Babashka. A disposable
probe confirms descriptor-backed iteration and distinct replacement identity
on Java 21 and Babashka without another helper runtime.
Lifetime operations use an explicit Clojure lease protocol: Babashka does not
support implementing Java Closeable on a custom deftype or reify.
The qualified implementations retain native directory state until explicit
close, including after iteration reaches EOF; see OpenJDK 21's
[UnixDirectoryStream](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixDirectoryStream.java)
and [UnixSecureDirectoryStream](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixSecureDirectoryStream.java).

## Testing plan

Use disposable local directories. Cover descriptor/path disagreement, deletion
and recreation, closed pins, refusal cleanup and protected-child boundaries.
Retain native ACL checks, both JVM product contexts, source Babashka and a fresh
package. Independent negative controls must fail the intended assertions.

Validation completed on the actual native filesystem:

- 12 tests / 34 assertions in each of four contexts: explicit Java 21 in both
  products, source Babashka, and a fresh class-stripped CLI package.
- Both Java contexts also passed with local compiled-class directories excluded,
  checking cold source loading rather than relying on cached deftype classes.
- Six independent negative controls failed with zero errors on Java 21 and
  Babashka: descriptor witness (1), traversal fuel (1), native cleanup (2),
  closed-state refusal (1), marker cleanup (1), and child-device binding (1).
- Nine exact safe REPL examples passed on Java 21 and Babashka.

These are disposable local directory/process checks, not live provider actions.

## Deployment plan

No provider or infrastructure actions. The host owns resource lifetimes and
must close pins only after dependent operations are finished. Qualification
will integrate ownership and failure cleanup in #2555. This does not authorize
effects, replace durable run fences, or attest power-loss behavior.

## Related issues/PRs

Merged #2533 provides protected directories and ACL checks; merged #2552 provides
backend observations. The inode-reuse finding in PR #2555 needs this
prerequisite; qualified-root lifetime integration remains there.

## Checklist

- [x] Implementation, native proof and owner standards/adversarial review.
- [ ] Current-head CI, automatic and full reviews, all threads resolved.
