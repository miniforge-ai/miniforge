<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: query bounded Prometheus measurement windows for OPSV

## Overview

Concrete read-only HTTP transport for the approved Kubernetes + Prometheus
backend. The bounded-measurement foundation, #2310, is merged and integrated.
No cluster changes or live experiments are authorized.

## Motivation

The measurement contract rejects incomplete or ambiguous data. The backend
also needs a real client that requests those declared windows without leaking
credentials or hanging indefinitely on an unavailable metric server.

## Changes in Detail

- Keep endpoint, timeout and response limits in validated operational data.
- Request explicit range queries without following credential-bearing redirects.
- Bound response acquisition, sanitize failures and preserve cancellation.
- Pass successful native JSON responses through the shared measurement contract.

## Testing Plan

Inject transport boundaries and use deterministic byte-stream fixtures. Verify
timeouts, size limits, HTTP errors, malformed JSON, redirects and resource
cleanup without contacting a metric server. Run consuming-product, packaged,
standards and normal signed-hook gates.

Native-runtime source acceptance passes 39 tests and 207 assertions. These
tests use in-memory bodies and an explicit external-response projection, without
network calls, JVM processes or test repositories. This is not packaged proof.
The separate JVM fixture exercises actual JDK responses and stream closure.

Adversarial review found two library-contract pitfalls. Cheshire's strict parser
makes arrays eager but does not enforce one document or unique object keys.
The body boundary now rejects trailing documents, duplicate keys and invalid UTF-8.
Malli regex validation searches for matches, so bearer-token validation now
anchors the whole value and rejects control characters before transport.
Interrupt tests capture the flag before test reporting can consume it.
An expired body deadline also prevents reader-thread creation.

Reconciliation with merged main preserves the reviewed sample-limit regression.
Both consuming product suites are checked after foundation integration. A fresh
stripped CLI jar must pass isolated acceptance without production-source overlays.
The catalog regression covers every new transport message.

Normal merge hooks also found inherited lint debt. Six overlong sentences in
the merged sink-split document were shortened without changing its claims.
An unused release-sandbox test binding was renamed without changing behavior.

Copilot identified rounded wire timestamps and wrapped HTTP timeout classification.
The producer-shaped JVM regressions first failed five assertions, without errors.
Exact decimal decoding now follows duplicate-key and single-document validation.
The portable JSON release avoids generated interfaces unavailable in Babashka.
Cause selection reuses cycle-safe cleanup traversal, preserving fatal and interruption priority.
Cleanup passes in all four products, and workflow passes in all three.
The final pass also reproduced synchronous timeout masking of a fatal cause.
Removing the special timeout catch routes every failure through shared cause priority.
Focused JVM acceptance separately checks actual JDK responses and cleanup.
A real blocked pipe now tests deadline expiry after reader entry, original-task
cancellation and owned stream closure. Promise waits are bounded; no sleeps are used.
Disabling cancellation or release independently makes its corresponding assertion fail.
Endpoint admission also rejects URI ports above the protocol range before transport.
Settings preserve wrapped critical causes and sanitize structured configuration
failures. The observation boundary also handles structured transport wrappers
without losing timeout, fatal or interruption priority. The new cases first
reproduced three failing assertions and two escaping errors.
Upstream warning-test fixtures now reuse the acknowledged gate context instead
of claiming durable publication from a sinkless stream. Production remains fail-closed.

The merged settings foundation, PR #2327, owns endpoint admission, URI scheme
canonicalization, valid port bounds and RFC 6750 Bearer grammar. Shared fixtures
preserve its structured-cause tests alongside transport and reader regressions.

## Deployment Plan

Staging and recommend-only remain backend defaults. Reading measurements grants
no load, deployment, apply, PR or rollback authority. Kubernetes fingerprints,
guarded experiments and the command surfaces remain separate integration work.

## Related Issues/PRs

Follows `feat/opsv-kubernetes-observation`, the bounded measurement contract.
Uses the public OPSV contract rather than rebuilding its schemas or factories.

## Checklist

- [x] Concrete transport and deterministic failure regressions
- [x] Consuming-product and packaged acceptance
- [x] Adversarial standards review and normal gates
- [ ] Fresh Copilot review and all CI gates
