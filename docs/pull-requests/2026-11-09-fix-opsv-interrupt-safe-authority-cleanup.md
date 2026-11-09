<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(opsv): revoke partially registered authority despite interruption

## Overview

Share exception-safe authority abandonment with the governed native runtime.
This is internal cleanup, not new execution authority or a public API.

## Motivation

Review of #2490 found a pre-existing registration failure path.
The authority file can be linked before an interrupted durability check refuses
registration. Revocation must run without the interrupt flag blocking file IO.

## Changes in Detail

Extract the existing abandonment operation and keep its PR callers live.
Temporarily clear an existing interrupt for exact-grant revocation and restore
it afterward, including exceptional exits. Retain the validated issuance time,
original failure, exact grant ID and independent revocation-failure diagnostics.
Successful registration still returns unchanged; cleanup never expands scope.

## Testing Plan

Interrupt the actual grant publication's post-link ancestry synchronization.
Use real issuance, registration, storage, revocation and existing PR orchestration.
Assert refusal, exact stored revocation, preserved interruption and no provider IO.
Check source and isolated package behavior, meaningful negative fault detection,
both JVM products, strict lint, strata, structure and fresh CLI packaging serially.

Source and the freshly rebuilt isolated package each pass 32 PR tests / 221
assertions. Restoring unprotected revocation produces exactly two assertion
failures and zero errors. Both JVM products, strict lint, inferred strata,
structure and task classpaths pass. A sandbox cache-write denial interrupted
classpath validation; only the remaining checks were resumed with cache access.

## Deployment Plan

No live calls, experiments, cluster changes or execution activation.

## Related Issues/PRs

Prerequisite for #2490; fixes the same existing PR registration cleanup path.

## Checklist

- [x] Implement shared interrupt-safe cleanup and the post-link regression.
- [x] Source, JVM, package and negative-fault validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Signed commit, full current-head review, all CI and owner merge.
