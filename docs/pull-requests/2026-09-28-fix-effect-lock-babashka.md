<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Release effect locks through their owning channel

## Overview

Use the dedicated FileChannel lifetime to release transaction locks on every exit.
Remove the unsupported direct call to the concrete FileLock implementation.

## Motivation

Packaged OPSV execution failed before provider I/O because Babashka disallows
`sun.nio.ch.FileLockImpl.release`. A durable transaction could remain committing
even though no provider call occurred. JVM tests did not expose this runtime gap.

## Layer and dependencies

Effect-transaction filesystem persistence only. No new dependency or public API.

## Changes in detail

The existing `with-open` owns one channel per lock scope. Closing it releases
its lock on success, anomaly return, exception or error. Preserve nonblocking
contention behavior and propagate the original application failure unchanged.

## Testing plan

All three consuming projects pass 59 tests and 210 assertions each.
Two dedicated lock tests pass under Babashka with seven assertions, proving
exclusion, release and original exception/error identity. Scoped standards
report zero violations across 23 files. Rebuild and rerun packaged OPSV audit
tests with the fix integrated before its application PR merges.

## Standards adversarial pass

Remove a redundant resource-management layer instead of adding runtime branches.
Keep the channel owner responsible for cleanup and preserve exception boundaries.
No duplicated maps, new messages, reflective implementation calls or hook overrides.

## Deployment plan

Existing uncertain transactions still require read-only reconciliation; this fix
does not retry them. Future lock scopes release through their dedicated channel.

## Related issues/PRs

Found during packaged acceptance testing of the N7 disposition-audit work.

## Checklist

- [x] JVM consumers and Babashka lock regressions pass
- [x] Scoped standards pass
- [ ] Packaged application acceptance, final review and CI complete
