<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Normalize schema-valid SQL timestamps

## Overview

Normalize SQL Date and Time values admitted by `inst?` without throwing from
their unsupported `toInstant` methods. Preserve SQL Timestamp nanosecond precision.

## Layer and dependencies

Independent execution-grant foundation. Extracted from PR #1929 so its remaining
storage-boundary fixes stay within the PR budget. No public API change.

## Changes

Use the existing instant conversion, falling back to epoch milliseconds only
when a Date implementation explicitly does not support `toInstant`.
Add regressions for Date, Time and Timestamp.

## Verification

Run execution-grant tests in every consuming project, Miniforge standards scan,
Polylith checks, Clojure lint, stratification, commit hooks and CI.

## Adversarial review

The fallback does not truncate Timestamp precision or catch unrelated failures.
It stays in the shared timestamp primitive, preserving callers and per-file strata.

## Deployment and rollback

No migration or configuration change. Reverting restores the earlier conversion.

## Related work

PR #1929: durable runtime grant authority.
