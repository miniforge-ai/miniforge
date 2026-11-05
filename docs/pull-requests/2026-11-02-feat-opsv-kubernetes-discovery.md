<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: correlate read-only Kubernetes scaling signals

## Overview

Acquire the declared target fingerprint and two explicitly queried candidate
drivers. Require cluster, namespace and service label correlation for each window.

## Motivation

A complete metric series alone does not establish which service it describes.
Discovery must not present an unrelated series as the target's scaling signal.

## Changes in Detail

Operator configuration names the service, both endpoints, CPU/backlog queries and
three distinct label names. CPU and backlog queries must differ.
No query, target or threshold is guessed. Each existing acquisition boundary
retains its own request validation and bounds.
Read failures and mismatched labels stop subsequent acquisition. Results preserve
the declared query, window, labels and samples without claiming verification.

The typed capture (#2350) and Prometheus transport floors are merged. The future
guarded runner and adapter factory will consume this read-only discovery API.
Operator target mapping is trusted; matching labels do not establish arbitrary
query semantics independently.

## Testing Plan

Composition tests use values produced by the real captured decoder and measurement
decoder. Transport ports are substituted; existing transport suites cover HTTP.
Test label mismatch, missing labels, each read failure, early stop and critical
cause propagation and duplicate-query refusal. Both products, normal hooks and
the combined isolated package passed. Collected counts are in the PR body.

## Deployment Plan

Implementation only. No live cluster calls, load, mutation or credential discovery.
This does not complete N7's guarded runner, verification or Fleet surfaces.

## Related Issues/PRs

N7 §3.1 DISCOVER; bounded capture #2350; dependency foundation #2343.

## Checklist

- [x] Focused and packaged tests
- [x] Adversarial standards review and signed hooks
- [ ] Fresh full Copilot review and all CI gates
