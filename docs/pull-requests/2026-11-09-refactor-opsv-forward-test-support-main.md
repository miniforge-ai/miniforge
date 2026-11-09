<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(opsv): share governed forward-runtime test support

## Overview

Extract the existing host and HTTP test fixtures without changing production code
or public API behavior. Keep the governed-start tests as live consumers.

## Motivation

Inert creation needs the same real runtime, context, supervisor and audit setup.
Separate this test-support prerequisite to keep the implementation PR bounded.

## Changes in Detail

Share runtime construction, disposition/assembly reads and simulated HTTP
dispatch. Retain native-start response construction and ownership routes at
their existing edge. Bind host clock/context readers with named `partial`
adapters, not anonymous functions embedded in maps.

## Testing Plan

Use the existing real grant, transaction, N4/N8 and evidence producers with
simulated HTTP. Recheck source and isolated-package start/PR regressions and
all nine relevant faults. Also check strict lint/strata, both JVM products, structure,
task classpaths and fresh CLI packaging under the shared serial protocol.

Source and a freshly rebuilt isolated package each pass 47 tests / 629 assertions
and all nine fault checks with assertion failures and zero errors. Both JVM
products, structure, strict lint/strata and task classpaths pass.

## Deployment Plan

Test support only. No production activation, live calls or cluster changes.

## Related Issues/PRs

Base: main after merged #2490. Supports the inert-creation runtime.

## Checklist

- [x] Extract live, shared test support and named host-reader adapters.
- [x] Source, JVM, package and meaningful fault validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Signed commit, current-head full review, all CI and owner merge.
