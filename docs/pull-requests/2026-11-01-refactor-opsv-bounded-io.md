<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: share bounded OPSV response I/O

## Overview

Give the existing deadline, future ownership and bounded UTF-8 response reader
a backend-neutral namespace. Prometheus remains its current production consumer.

## Motivation

Kubernetes acquisition needs the same bounded reads and critical-failure cleanup.
Reuse the reviewed implementation rather than duplicating a transport framework.

## Changes in Detail

Rename the internal I/O namespace and update every producer, consumer and test.
This is pure restructuring: no arities, defaults, failure precedence or resource
ownership change. No new public interface, dependency or operational authority.

## Testing Plan

Trace original futures, reader cancellation, stream closure and expired deadlines.
Run both OPSV products, focused JVM tests and fresh isolated package tests.
Keep normal hooks, signing, review and CI gates.

## Deployment Plan

No migration or configuration change; the public interface is unchanged.

## Related Issues/PRs

Builds on merged #2314 and supports the approved Kubernetes acquisition backend.

## Checklist

- [ ] Owner adversarial and standards review
- [ ] Local product, JVM and isolated package verification
- [ ] Fresh full Copilot review and all CI
