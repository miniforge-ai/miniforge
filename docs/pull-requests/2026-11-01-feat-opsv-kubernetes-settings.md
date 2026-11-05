<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: admit explicit Kubernetes acquisition settings

## Overview

Admit a bounded, explicit HTTPS Kubernetes API target. This foundation does not
read resources, discover credentials or grant execution authority.

## Motivation

Keep target admission independent of transport and capture orchestration so each
PR remains small and reviewable. Operational bounds belong in schema-checked EDN.

## Changes in Detail

Require an explicit cluster identity, namespace, Pod selector and node-pool label.
Reject credential-bearing URLs, query fragments, non-origin paths and invalid ports.
Share Bearer-token, timeout and byte-bound schemas with existing Prometheus settings.
Preserve critical failure and interruption handling at the configuration boundary.

## Testing Plan

Test defaults, missing targets, malformed namespaces/endpoints, sanitized errors,
and critical causes. Run both OPSV products, a fresh isolated package, normal
signed hooks, scanner, size gates, full review and CI.

## Deployment Plan

No live calls or migration. Staging and recommend-only backend defaults are unchanged.

## Related Issues/PRs

Supports N7.EX.1 and the approved Kubernetes + Prometheus backend; follows #2314.

## Checklist

- [ ] Adversarial and applicable standards review
- [ ] Local products, native package and normal signed hooks
- [ ] Fresh full Copilot review and all CI
