<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Issue exact temporary OPSV experiment grants

## Overview

Domain/application grant issuance, API surface class 1, internal EDN.
Built on #2392's merged exact authority foundation.
Current main is integrated through `9f378a561`.
Documentation-only prerequisite #2402 carries incoming prose-hook repairs separately.

## Motivation

The production issuer must derive experiment authority from a runtime-attested
request, not inherit generic deployment policy or accept caller-selected ceilings.
Keep the existing issuer and eligibility checks; do not introduce a parallel broker.

## Changes in Detail

Extend the closed canonical request and explicit runtime policy profile for
temporary staging effects. Preserve generic request compatibility and derive
root, nondelegable, one-effect authority with bounded expiry and exact bindings.
Decompose the existing nested issuance boundary while retaining its error contract.
Share operation, resource-identity and load-bound policy between issuance and authorization.
Derive expiry from the declared duration, capped by the existing 30-minute runtime policy.
Typed semantic refusals and denied preflight return unauthorized; malformed requests return invalid-input.

This does not establish staging/ownership, implement N4/N8 admission, enable a
provider, or authorize live effects. Runtime composition must obtain real
preflight and commit-time fencing before any future provider use.

## Testing Plan

Pin valid trial/load/recovery issuance and fail closed for incomplete, denied,
ineligible, cross-class, or caller-expanded authority. Regress all existing
issuer requests, durable current-authority composition, and packaged behavior.
Use strict lint, true inferred strata, all affected product contexts, targeted
faults, and serialized normal signed hooks; retain all PR review/CI gates.

Cheap source checks pass 40 tests/492 assertions, including real breach-history,
independent recovery, durable commit composition, and existing issuer compatibility.
Strict kondo is clean. All 13 changed namespaces pass true inferred strata, at most three.
Fourteen deliberate authority faults cause assertion failures with zero errors.
The published issuer rejects all five valid operation requests: five assertion failures, zero errors.
Seven pure unit tests retain 93 assertions; the slowest measured run takes 1.131 milliseconds.
Compile the validator once, matching production, rather than rebuilding it per assertion.
The existing constructor instrumentation now targets the canonical core factory after decomposition.

Full local validation passes after integrating current main:

- Both affected bricks pass in Miniforge, Core, TUI, and Data Foundry.
- Durable authority and CLI startup integration: 13 tests, 137 assertions.
- All 22 scoped OPSV/workflow/N6 integration suites: 97 tests, 1,240 assertions.
- Fresh isolated JAR, with test paths only: 153 tests, 996 assertions on the JVM.
- Native packaged OPSV regressions: 308 tests, 1,903 assertions on Babashka.
- All task entrypoints load; the CLI builds from current sources.
- Fourteen authority faults are detected by assertion failures, each with zero errors.
- Seven pure tests retain 93 assertions; the slowest measured run is 0.813 milliseconds.
- Committed scans find no violations across 44 grant, 24 effect, and four project files.

Current-main hooks exposed one unused test import, which is removed.
Its strict lint and inferred strata pass; its scoped regression passes two tests and seven assertions.
The issuer's 13 namespaces also pass strict lint and true inferred strata, with no layer waivers.
The PR uses 383 of 600 reportable lines, without exceptions.
Incoming prose findings are fixed in #2402 without weakening the hook.

## Deployment Plan

No default activation or live effects. Require #2402 and all normal owner merge gates.

## Related Issues/PRs

N7 completion; approved authority amendment #2381 and scope foundation #2392.

## Checklist

- [x] Implement and adversarially review the existing-issuer extension.
- [x] Validate all affected products, package, faults, and compatibility.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
