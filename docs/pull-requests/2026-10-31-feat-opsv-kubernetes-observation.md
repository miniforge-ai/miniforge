<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: decode bounded Prometheus measurement windows for OPSV

## Overview

First measurement contract for the approved Kubernetes + Prometheus OPSV
backend. Decode a parsed Prometheus range response against a predeclared query
and bounded time grid. No mutation authority or experiment execution is claimed.

## Motivation

The simulated adapter cannot establish fresh external observations. Concrete
execution needs trustworthy wire decoding before HTTP transport, Kubernetes
identity capture and guarded load orchestration can consume those observations.

## Changes in Detail

- Preserve the declared query, exact measurement grid, series labels and samples.
- Require one aggregated float series; never guess how to combine label sets.
- Reject incomplete grids, nonfinite values, histograms and API annotations.
- Keep validation at interface and external-response boundaries.
- Place the contract beside the existing OPSV adapter and verification contracts,
  rather than introducing a standalone component without a concrete consumer.

## Testing Plan

Use producer-shaped wire fixtures without network calls. Run
project-context tests, normal hooks, standards review and packaged acceptance.
No cluster access or live load is authorized by this implementation work.

The initial standalone composition passed seven tests and 49 assertions but
reported an unnecessary-component warning. The measurement contract now lives
in `phase-opsv`, its existing contract owner. No warning suppression or
decorative consumer was added. Shared factories mirror Prometheus range vectors;
the diagnostic catalog has an explicit resource-loading regression.

The final component layout passes workspace validation without that warning.
All `phase-opsv` tests pass in both Miniforge product compositions (54s),
including the seven new measurement tests and 49 assertions per composition.
Lint reports zero warnings/errors and inferred namespace depth stays within
three strata. Numeric timestamp comparison avoids lossy float coercion.

## Deployment Plan

Staging and recommend-only remain the intended backend defaults. This pure
decoder does not contact a cluster or metric server, run experiments or issue
grants. Concrete transport, Kubernetes identity and guarded experiments follow
as separate reviewed changes; this PR is not a live-backend completion claim.

## Related Issues/PRs

N7 environment fingerprints, instrumentation and verification requirements.
Protocol reference: [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/).

## Checklist

- [ ] Boundary implementation and failure regressions
- [ ] Affected-project and packaged tests
- [ ] Adversarial standards review and normal gates
- [ ] Fresh Copilot review and all CI gates
