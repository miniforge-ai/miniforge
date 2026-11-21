<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: update N7 conformance evidence

## Overview

Update the informative implementation annex to distinguish merged and
tested capabilities from end-to-end acceptance still outstanding.

## Motivation

The previous snapshot predated the governed GitHub adapter and the
native Kubernetes/Prometheus work. Reporting them as absent obscures
the remaining integration work.

## Changes in detail

Keep all normative requirements and the existing authority defaults.
Summarize the simulated workflow, exact governed PR path and native
staging primitives separately. Identify full-stop recovery, native
workflow acceptance, Fleet surfaces, and drift as open.

## Testing plan

Trace each new statement to merged implementation and test evidence.
Verify that all text outside Annex A is unchanged and run Markdown and
the normal repository checks.

## Deployment plan

Documentation only. No live cluster, provider, or configuration changes.

## Related issues/PRs

Based on `main`. See #1935, #2380, #2493, #2495, #2497, #2498 and #2528.

## Checklist

- [x] Confirm each statement against main.
- [x] Confirm that text outside the informative annex is unchanged.
