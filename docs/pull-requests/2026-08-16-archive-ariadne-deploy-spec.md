<!--
  Title: Archive completed Ariadne deploy-grant work
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(work): archive completed deploy-grant spec

## Layer

Work tracking and documentation.

## Overview

Archive the Ariadne step 2d Kubernetes deployment spec after its governed
implementation merged in #1794. Remove the now-empty active queue theme so the
queue reflects only unfinished work.

## Changes

- Move `ariadne-deploy-grant-enforcement.spec.edn` to `work/done/` without
  changing its completed contract.
- Remove the completed `ariadne-grants` theme from `work/QUEUE.md`.

## Verification

- `bb fmt:md`
- `bb pre-commit`
- Review that the archived spec content is unchanged and the queue contains no
  stale reference.
