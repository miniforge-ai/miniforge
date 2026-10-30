<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(execution-grant): sign grants at issue; refuse unsigned grants at effect commit

PR #2270.

## Overview

Grants are signed by the runtime key when issued or delegated, and effect commit refuses a grant whose signature does
not verify. Stacked on #2269 (runtime-signing component); retarget to `main` after it merges.

## Motivation

A grant record was trusted on its contents. A grant written into the grant store by hand, or one whose expiry, scope or
ceilings were edited after issue, authorized an effect exactly like one the runtime issued.

## Changes in Detail

1. `ExecutionGrant` gains optional `:grant/signature` (64 hex).
2. `grant/issue` and `grant/delegate` sign: HMAC-SHA256 under the runtime key over the grant's issued fields. Revocation
  fields are outside the signature because the store records revocation as a separate marker after issue; a revoked
  grant still verifies as the grant the runtime issued. A runtime key that cannot load fails issuance with its anomaly.
3. `grant/signed?` verifies a grant against the runtime key; false when the key cannot load.
4. `effect-transaction` authorization checks the signature before scope and ceilings, on both commit paths. An unsigned
  or altered grant is denied with outcome `:unsigned`, and the record goes to `:failed` without running the effect.
5. runtime-signing joins the projects that include execution-grant (miniforge, miniforge-core, miniforge-tui,
  data-foundry), the `bb miniforge` task classpath, and the conformance alias.

Known limit: deleting a grant's revocation marker file is not detected by the signature; the grant reads as live again.
Detecting that needs an append-only or signed revocation record.

## Testing Plan

1. `effect-transaction/signed_grant_test`: a grant with its signature removed, with its expiry extended after issue, or
  with a foreign signature never runs the effect and records `:failed`; a runtime-signed grant commits. Fails on main (5
  failures): the effect runs.
2. `execution-grant/signature_test`: issued grants are signed; a changed scope or a removed signature does not verify;
  revocation keeps the signature valid; a delegated child gets its own signature.
3. Ran locally: every test namespace in execution-grant, effect-transaction, opsv-actuation, phase-opsv,
  phase-deployment, and pr-lifecycle's merge tests, 413 tests, 0 failures, plus the two new namespaces. Pre-commit
  graalvm suite passes (execution-grant now loads runtime-signing under bb).

## Deployment Plan

The first grant issued creates `<miniforge-home>/keys/runtime-signing.key` (owner-only). Grants issued before this
change carry no signature and are refused at commit; they expire within 30 minutes. Test runs also create the key under
the home directory in use.

## Related Issues/PRs

Stacked on #2269. Envelope signing follows.

## Checklist

- [x] Regression test fails on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] Runs under babashka
- [x] PR doc: `docs/pull-requests/2026-10-30-feat-signed-grants.md` (rule 721)
