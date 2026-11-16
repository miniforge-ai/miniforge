<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: verify macOS storage ACLs before publication

## Overview

On macOS, check access control lists on the resolved publication root and its
ancestors before the publication probe writes any scratch entries.

## Motivation

An ACL can grant permissions absent from the POSIX mode. A mode-only check
cannot qualify macOS storage for a durable run fence.

## Changes in detail

Foundation layer, building on #2532. Use bounded, read-only Darwin filesec
queries through the system script runner, with paths passed as arguments.
A successful metadata query must confirm absence or read and check every ACE.
Refuse additive ACLs, native read errors, or an absent or malformed
acknowledgement. The check supplements POSIX
policy. It does not change permissions or establish volume ownership, persistence,
or cross-process locking.

## Testing plan

Run the file-durability suites in both JVM products, Babashka source, and a
fresh package. A disposable macOS fixture verifies that adding an allow ACE
without changing the POSIX mode changes the probe's result. Omitting the ACL
check must make this regression fail. Exercise a real native read error and
reject missing and malformed acknowledgements before creating scratch files.
Verify that timed-out children terminate, a timed-out capture is cancelled,
and a failed child never yields an acknowledgement.

A scoped GitHub-hosted macOS job checks out the exact PR head.
It runs the native fixtures in both JVM products, Babashka source,
and the fresh package. Run the task locally with `bb test:darwin-storage`.

Local results: 27 tests and 112 assertions passed in each of the four runs.
Disabling the ACL guard, child deadline or capture cancellation failed the
corresponding assertions without runtime errors. Strict lint, inferred
namespace depths, Polylith, and Babashka classpaths passed.

## Deployment plan

No configuration or live infrastructure changes. Existing macOS paths with
additive ACLs must use a different protected path for storage qualification.

## Related issues/PRs

Follows #2532; prerequisite for storage qualification and #2530.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
