<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: share OPSV HTTP ingress primitives

## Overview

Separate generic HTTP response ownership and strict JSON admission from
Prometheus request construction. Prometheus remains the production consumer.

## Motivation

Kubernetes acquisition needs the same redirect refusal, raw-future cancellation,
stream closure and strict JSON decoding. Keep one implementation of each policy.

## Changes in Detail

Pure restructuring inside `phase-opsv`: move existing transport helpers and the
strict JSON parser into backend-neutral internal namespaces. Keep public APIs,
validation, limits, failure precedence and wire semantics unchanged.

## Testing Plan

Compare moved executable forms and trace all original consumers at their new
sites. Run both product contexts, actual JVM cleanup tests, isolated native
package tests, normal signed hooks, scanner and size gates.

## Deployment Plan

No configuration, migration, dependency or authority change. No live calls.

## Related Issues/PRs

Builds on #2314; complements #2339 for the approved Kubernetes acquisition backend.

## Checklist

- [ ] Owner adversarial and standards review
- [ ] Product, JVM and isolated package checks
- [ ] Fresh full Copilot review and all CI
