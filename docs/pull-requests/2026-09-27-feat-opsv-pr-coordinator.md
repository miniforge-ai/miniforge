<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Govern OPSV PR execution through durable transactions

## Overview

Add the application coordinator between prepared OPSV PRs and provider adapters.
The API is class 1: trusted-process EDN, not a model-callable authority surface.

## Motivation

Prepared payloads and grants do not themselves execute governed effects. The
runtime must persist their correlation, reload current authority, and execute
only the exact durable payload while preserving uncertain provider outcomes.

## Layer and dependencies

Application layer, depending on merged PRs #1911, #1927, #1929 and #1931.
The subsequent provider adapter and runtime wiring depend on this coordinator.

## Changes in detail

- Persist a prepared payload with its evidence bundle and allowing envelope.
- Reload durable grant authority at commit time and reject invalid stored payloads.
- Pass the claimed transaction and exact payload to the trusted provider port.
- Never retry a claimed transaction or convert an uncertain response to success.
- Persist the envelope timestamp at standard EDN `#inst` millisecond precision;
  retain all decision fields and its UUID. Grant timing keeps its own precision.

## Testing plan

Exercise durable registration, proposal, commit, revocation, expiry, repeated
execution, failed verification drafts, malformed records and uncertain outcomes.
All 18 tests / 150 assertions pass, including boundary regression coverage.
The component standards scan reports zero findings. The CLI builds successfully
and packaged help runs. Polylith, kondo and stratum checks pass.

## Deployment plan

No external mode is enabled by this PR. Runtime wiring must supply evaluated
policy envelopes, scoped grants, provider ports and safe-boundary fencing.
The local stores require runtime-owned, trusted directories.

## Related issues/PRs

N7 governed actuation; PRs #1911, #1927, #1929 and #1931.

## Checklist

- [x] Regression tests and standards pass
- [ ] Signed commits and green CI
- [ ] Review findings fixed and resolved
