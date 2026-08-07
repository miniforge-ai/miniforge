<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: enforce execution-grant scope

## Overview

Make an ExecutionGrant's scope part of authorization and bind commit-time
authorization to the durable effect proposal before production issuance is
enabled.

## Motivation

The grant record currently carries `:grant/scope`, but authorization checks only
liveness and numeric ceilings. A grant scoped to one PR can therefore authorize
another. Issuing production grants on this foundation would preserve ambient
authority behind a structured record.

## Layer

Authority contract and its EffectTransaction application adapter. These land
atomically because enabling the pure check alone denies every existing scoped
transaction, while adapting commit without the check enforces nothing.

## Depends on

- PR #1681 (Ariadne governed-effect evidence contract) — merged
- PR #1683 (EffectTransaction standards remediation) — merged

## Changes in Detail

- Compare the proposed effect against grant scope at every authorization check.
- Translate scope mismatches into an explicit deny-class reason.
- Derive commit-time scope from the durable effect proposal, ignoring a
  caller-supplied scope map.
- Replace duplicated multi-form effect callbacks with one named test fixture.
- Replace duplicated gate reason maps with one constructor, decompose
  multi-step translation callbacks, and move developer prose to the system
  catalog.
- Split grant-result translation from the decision kernel after stratum lint
  exposed five real layers in the original namespace.

## Architecture

`scope-widened?` remains the one containment primitive. Delegation and effect
authorization use it in the same direction: the candidate must carry every
binding established by its authority. The grant component remains pure and
does not depend on transaction storage. EffectTransaction is the application
boundary that adapts its durable proposal into that pure authorization input.

The easy design would authorize a caller-supplied scope alongside the durable
proposal. The simpler design has one canonical authority: immutable proposal
data persisted by EffectTransaction. Scope policy remains pure in
execution-grant; filesystem state remains confined to the transaction store.

## Testing Plan

- Execution-grant schema, delegation, scope, expiry, revocation, and ceilings.
- EffectTransaction success, failure, interruption, scope refusal, and
  reconciliation paths.
- Gate translation and DecisionEnvelope worst-wins derivation.
- `bb pre-commit`, `bb review`, Polylith checks, and affected builds.

Exact-head validation completed with zero kondo warnings, clean stratum lint,
the focused gate suite in all composed projects, and the full five-project
stable-derived suite (12m20s). `bb review` remains at the known repository
baseline of 78 findings and reports no branch-introduced violation.

## Deployment Plan

No deployment action is required. Production call sites still use the explicit
`:authority/unenforced` path until the dependent issuance slice lands.

## Security Considerations

This closes grant reuse and prevents callers from substituting a matching scope
map for a different durable proposal. Production issuance remains blocked until
the separate issuer slice chooses principals, ceilings, eligibility, and TTLs.

## Checklist

- [x] Non-empty grant scope fails closed when effect scope is absent.
- [x] Mismatched effect scope produces an explicit deny envelope.
- [x] Commit uses durable proposal scope and never invokes a refused effect.
- [x] Focused and full checks are green.
- [x] Adversarial standards pass is clean.

## Related Issues/PRs

- `work/ariadne-grant-issuance.spec.edn`
- `work/n07-opsv-governed-actuation.spec.edn`
