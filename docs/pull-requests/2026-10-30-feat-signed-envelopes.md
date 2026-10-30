<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(effect-transaction): sign allowing envelopes at mint; refuse unsigned envelopes at commit

PR #2275.

## Overview

The decision envelope that allows an effect is signed by the runtime key when it is minted for that effect, kept on the
durable proposal, and verified at commit. Stacked on #2270 (signed grants) and #2269 (runtime-signing); retarget as
those merge.

## Motivation

An effect record held only the envelope id. A record written outside the runtime could name any envelope id, and commit
never looked at the envelope, so the record did not show that the runtime ever decided to allow the effect.

## Changes in Detail

1. `DecisionEnvelope` gains optional `:envelope/signature` (64 hex). Envelopes that do not authorize an effect (phase
  gates) stay unsigned and valid.
2. effect-transaction `sign-envelope` signs an envelope with the runtime key; when the key cannot load, the envelope
  stays unsigned and commit refuses it. `envelope-signed?` verifies one.
3. `propose!` takes `:envelope` and keeps it on the record as `:effect/envelope`. The codec writes its `:envelope/at` as
  ISO text, like the record's own timestamps; a nested `Instant` was written as `#object[...]` and the record did not
  read back.
4. Commit refuses with `:failed`, outcome `:envelope-unsigned`, when the proposal's envelope is missing, names another
  id than `:effect/envelope-id`, does not allow, or does not verify. It runs right after the grant signature check, on
  both commit paths. Release effects committed with `:authority/unenforced` are not checked, as before.
5. Mint sites: merge and deploy sign the envelope `gate/decide` returns in their authority `prepare`; OPSV governance
  signs the envelope it mints. Each passes the envelope to `propose!`.
6. `docs/architecture/rfc-ariadne-adoption.md` gains an implementation-status section: what "runtime-signed" means as
  built (one HMAC key, not public-key signatures), what is signed and where it is verified, which "attested" items
  remain targets, and the known limits.

## Testing Plan

1. `signed_envelope_test`: a proposal with no envelope, an unsigned one, one naming another id, a signed refusing one,
  and one altered from deny to allow after signing all record `:failed` without running the effect; a signed envelope
  with an `Instant` survives the store and still verifies.
2. Updated fixtures to propose with a signed allowing envelope: effect-transaction `fixtures/propose-merge!`,
  opsv-actuation `execution_fixtures/allowing-envelope`. `phase-opsv/governance_test` now also asserts the minted
  envelope is signed.
3. Ran locally: every test namespace in execution-grant, effect-transaction, opsv-actuation, phase-opsv,
  phase-deployment, decision-envelope, and pr-lifecycle's merge tests: 441 tests, 0 failures after the fixes above.

## Deployment Plan

Proposals written before this change carry no envelope and are refused at commit; their grants expire within 30 minutes.

## Related Issues/PRs

Stacked on #2270 and #2269.

## Checklist

- [x] Regression tests for each refusal case
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-feat-signed-envelopes.md` (rule 721)
