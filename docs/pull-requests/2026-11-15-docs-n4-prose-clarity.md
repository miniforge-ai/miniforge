<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: clarify N4 prose without changing policy

## Overview

Split hook-required prose cleanup out of #2528 so the approved full-stop
contract remains within its whole-PR size gate.

## Motivation

The prose hook checks the complete touched N4 document, including older text.
Sentence splits and review corrections are independently reviewable cleanup,
not new full-stop requirements or a size exception.

## Changes in detail

Documentation layer, based on current main; no prerequisites.
Preserve existing normative requirements and metadata. Clarify the informative
calibration annex against requires-calibration?, check, and the calibration
gate task. This does not extract a new normative calibration policy from code.
Full-stop permission and its spec version remain in #2528, not this PR.

## Testing plan

Read the complete actual diff and compare requirements against the original.
Run prose, Markdown, normal hooks, and current-head CI and review gates.

## Deployment plan

Documentation only; no runtime behavior, credentials, or live infrastructure changes.

## Related issues/PRs

PR #2528 depends on this cleanup to keep its own contract changes reviewable.

## Checklist

- [ ] Root adversarial standards review and local checks complete.
- [ ] Current-head CI and full review settled.

Review sources at the immutable standards pin:
[guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
Repository agents.md supplies the complete merge gate absent from that shared pin.
