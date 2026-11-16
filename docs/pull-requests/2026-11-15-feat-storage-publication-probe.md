<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: verify storage publication primitives

## Overview

Probe a protected POSIX directory for forced create-only publication and atomic
replacement. The probe uses disposable files and returns a result or anomaly.

## Motivation

Run authority needs both publication modes. POSIX permission support alone does
not establish their operation on the selected store.

## Changes in detail

Foundation layer; depends on #2529, merged. The internal API (surface class 1)
reuses the existing forcing primitives. It verifies create-only collision
refusal, successful replacement, and expected bytes. It never creates a
missing authority root or changes existing records.

## Testing plan

Run file-durability suites in both JVM products, Babashka source, and a fresh
packaged CLI. Inject omitted replacement and check that the positive test
fails an assertion. Also exercise directory sync failure and link collisions.

Local results: 17 tests and 90 assertions passed in each of the four runs.
The omitted-replacement fault failed an assertion in each run, without
runtime errors. Strict lint, namespace depth, Polylith, and classpaths passed.

## Deployment plan

No configuration changes. The probe does not qualify an authority store for
admission. Persistence, backend support, and cross-process lock checks are
separate requirements. No live experiments or cluster access are involved.

## Related issues/PRs

Prerequisite for the operator-approved storage qualification used by #2530.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).

## Checklist

- [x] Local proof passed.
- [ ] Exact-head CI and full review settled.
