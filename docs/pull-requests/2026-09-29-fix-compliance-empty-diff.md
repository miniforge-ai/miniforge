<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Preserve empty incremental standards scans

## Overview

A successful empty Git diff must select no changed files, not fall back to a
full-repository scan. This surfaced during the N7 standards adversarial pass.

## Changes

- Return an empty set for a successful diff without changed paths.
- Check the Git exit status before accepting output as a valid changed set.
- Preserve the existing full-scan fallback for invalid references and Git errors.
- Discard unused Git stderr at the process boundary so diagnostics cannot fill an
  unread pipe and deadlock either diff query. Keep stdout reserved for diff data.

## Standards adversarial pass

Keep Git retrieval in its existing scanner namespace and preserve the distinction
between unknown scope (`nil`) and known empty scope (`#{}`). Reuse the existing
isolated Git integration fixture; no new dependencies or duplicate constructors.
This does not exempt existing violations from an explicitly requested full scan.

## Testing

Exercise unchanged HEAD, a changed ancestor, a nonexistent reference and rejected
option-like input, including a long invalid ref whose error exceeds pipe capacity.
Confirm an unchanged incremental scan does not report the
fixture's deliberately noncompliant committed source. Run all scanner consumers,
repository hooks and final-head CI before merge.

## Deployment

No migration or operational changes. Only incremental review scope changes.
