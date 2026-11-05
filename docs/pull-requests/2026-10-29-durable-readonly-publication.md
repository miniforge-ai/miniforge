<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Confirm durable publication through the held writable channel

## Problem and contract

Reopening a published read-only checkpoint for writing fails after replacement.
Add `publish-bytes!` to retain the temporary file's writable channel across
caller-owned permission preparation and atomic rename. Force bytes before the
callback, force the same held file after publication, then sync destination
ancestry before acknowledging success. Return the destination File or an anomaly.

The caller owns path authorization, temporary reservation, rename, and cleanup.
The callback runs once and returns the destination File. A failed post-publication
confirmation remains an error even when the new file is visible. No automatic
mutation retry. Unsupported filesystem force operations remain errors.

Existing write and confirm APIs retain their behavior. Channel primitives and
publication composition occupy separate namespaces, each within three strata.
The immediate consumer is the checkpoint durability follow-up to #2094.

## Verification

The checkpoint consumer reproduces a read-only replacement confirmation failure.
Focused Babashka and JVM suites pass 11 tests and 61 assertions.
Coverage includes read-only permissions, force identity, failure cleanup, and symlink refusal.
It also covers previous-target preservation, failed directory confirmation, invalid
callbacks, complete byte replacement, and byte-preserving legacy confirmation.
All affected file-durability consumers pass serially in 10 seconds.
Normal signed hooks, standards, fresh review, and CI remain required.
