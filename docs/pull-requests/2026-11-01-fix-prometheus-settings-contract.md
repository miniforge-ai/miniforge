<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: define a critical-safe Prometheus settings contract

## Overview

Extract the validated operational settings foundation from #2314. This keeps
the transport PR within its ordinary size limit while fixing review findings.

## Motivation

Settings failures must remain sanitized without masking fatal errors or
cancellation. Structured configuration exceptions need the same boundary policy.

## Changes in Detail

- Validate explicit endpoints, bearer transport, timeout, byte and sample limits.
- Keep operational defaults in EDN; never discover endpoints or credentials.
- Preserve critical causes before converting ordinary failures to anomalies.
- Reuse shared, producer-shaped configuration fixtures.
- Canonicalize case-insensitive URI schemes without changing escaped paths.
- Reject reserved port zero and preserve omitted or valid explicit ports.

## Testing Plan

Run malformed settings, port bounds, structured failures and wrapped critical
causes. Verify both consuming products, native packaging, standards and normal hooks.

Review regressions first reproduced five failing assertions without errors.
Critical-cause coverage now includes ordinary and producer-shaped structured wrappers.

## Deployment Plan

This foundation performs no HTTP requests, experiments or mutations.
The transport in #2314 is its immediate consumer.

## Related Issues/PRs

Prerequisite for #2314, extracted after its final review identified boundary gaps.
Based directly on current main, not on the transport branch.

## Checklist

- [ ] Both product suites and native acceptance
- [ ] Adversarial standards review and normal signed hooks
- [ ] Fresh full review, resolved findings and all CI
