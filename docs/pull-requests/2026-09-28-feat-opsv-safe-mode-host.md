<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Connect host safe-mode to the OPSV stop domain

## Overview

Close OPSV admission and request active-run cleanup before the host changes to
safe-mode. Retain the cleanup report separately from event publication.

## Motivation

An event listener alone cannot enforce emergency stop when event delivery fails.
The process needs a direct connection between its reliability manager and the
registered OPSV runs. Mode entry does not by itself prove grant or load cleanup.

## Layer and dependencies

Reliability accepts an optional synchronous host callback, without depending on
OPSV. The CLI composes public OPSV interfaces through its existing optional
product-provider boundary. MiniForge Core remains usable without OPSV installed.

## Changes in detail

- Run the configured callback before the safe-mode state transition and events.
- Serialize transitions so concurrent entries cannot overwrite safe-mode or
  repeat the entry callback. Retain callback anomalies and interruption state.
- Expose the last stop result for cleanup diagnostics.
- Bind the CLI process context to one shared OPSV stop domain. Provide registration
  for prepared runs; attach the returned handles to trusted PR runtime options.
- Refuse empty operator exit justification or principal.
- Separate dependency signals, budget recommendations and configuration from the
  state machine. Replace its seven strata and long evaluator with small functions.

## Verification

Both Miniforge and Core distributable builds pass. The packaged host tests prove
that a stop during provider preflight prevents POST, and event publication failure
does not prevent local fences and abort requests. Core starts without OPSV.
All three reliability consumers pass 36 tests / 101 assertions, including budget
and concurrent-entry regressions. The packaged host and boundary suites pass
eight tests / 31 assertions. Polylith and kondo pass. Scoped reliability and CLI
runner standards report no findings across 22 and 40 files respectively.

## Standards adversarial pass

No product dependency in the reliability component or Core. No duplicated signal
maps or additional decision layer. Boundary failures use localized diagnostics.
All changed namespaces remain within three strata. Keep commits under 200
reportable lines and this PR under 600; split the refactor into staged slices.

## Remaining work

This adds the host connection, not the production load adapter or the six OPSV
commands. Prepared-run construction must call the host registration API. Adapter
abort callbacks acknowledge a request, not completed rollback. Stopped domains
and old grants never reopen merely because an operator exits safe-mode.
Fresh authority and a new run domain require a later explicit host workflow.

## Checklist

- [x] JVM and packaged host integration checks
- [x] Both product builds and Core-without-OPSV check
- [x] Final all-consumer regression run
- [x] Signed commits and repository hooks
- [ ] Settled review and all CI green
