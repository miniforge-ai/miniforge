<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Correlate OPSV PR candidates and durable outcomes

## Overview

Extract the pure candidate and outcome model from PR #1935 into a small prerequisite.
Keep runtime issuance, storage and provider effects in the dependent PR.

## Motivation

Runtime review fixes need complete regression coverage without exceeding the
600-line PR budget. The pure model is independently reviewable and testable.

## Changes

- Derive one stable effect identity per workflow and case-insensitive repository.
- Use locale-independent repository normalization and the shared workflow-ID resolver.
- Reject a prepared target that does not match the verified policy hash.
- Reject malformed workflow IDs and repositories before deriving replay identities.
- Project confirmed PR observations and their grant, envelope and effect references.
- Require a granted PR-create transaction, including for matched reconciliation.
- Permit the validated policy correlation hash in the proposal input contract;
  retain it through the governed receipt and bind it into the governance digest.
  Keep the provider payload hash unchanged. Do not discard policy correlation.
- Retain failed or uncertain transactions as anomaly data.

## Standards adversarial pass

Keep candidate and outcome construction in one pure model namespace. Reuse the
shared workflow identity and localized message catalogs. No external effects,
authority issuance or new library dependencies are introduced. The provider's
repository schema moves to the shared OPSV domain contract so candidate creation,
governed proposal validation and provider dispatch use the same shape. The provider
depends on the domain interface; the application does not depend on adapter internals.
The policy-hash contract is also shared by candidate, proposal and durable receipt
validation, preventing malformed correlation from receiving an effect identity.

## Testing

Run both phase consumers and the packaged model suite. Tests cover identity,
case folding, process locale, workflow-ID aliases, mismatches and outcome projection.
Repository hooks and final-head CI remain required before merge.

## Related work

Prerequisite split from #1935. It does not independently enable PR emission or
claim N7 completion.
