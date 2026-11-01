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
An actual JSON serialization/read round-trip also checks the external producer
shape. Response fields and metric labels retain native string keys, instead of
depending on a hybrid keyword/string fixture or recursively keywordizing labels.
Request and response admission share one external boundary, keeping the public
interface thin without an extra forwarding namespace.

After current-main alignment, both consuming product suites pass again. The
new contract tests pass eight tests and 52 assertions. A fresh CLI build
produced a 6,880,334-byte stripped jar. Isolated packaged acceptance, including
the existing seven-phase value flow, passes 13 tests and 84 assertions.
Only the built jar and test fixtures were on the classpath; production-source
overlays were absent. Normal signed commits, lint and inferred strata pass.

Copilot identified that sample validation preceded the configured size bound.
The regression reproduced one failed assertion after correcting its validator
spy to handle both Malli arities. Constant-time vector shape/count guards now
reject oversized responses before sample validation. Both consuming products
pass again, and fresh isolated packaged acceptance passes 14 tests with
86 assertions. Actual namespace depth remains within three strata.

## Deployment Plan

Staging and recommend-only remain the intended backend defaults. This pure
decoder does not contact a cluster or metric server, run experiments or issue
grants. Concrete transport, Kubernetes identity and guarded experiments follow
as separate reviewed changes; this PR is not a live-backend completion claim.

## Related Issues/PRs

N7 environment fingerprints, instrumentation and verification requirements.
Protocol reference: [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/).

## Checklist

- [x] Boundary implementation and failure regressions
- [x] Affected-project and packaged tests
- [x] Adversarial standards review and normal gates
- [ ] Fresh Copilot review and all CI gates
