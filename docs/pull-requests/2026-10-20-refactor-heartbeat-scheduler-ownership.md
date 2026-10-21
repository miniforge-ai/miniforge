<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: isolate heartbeat scheduler ownership

## Overview and motivation

Separate executor allocation, scheduling and release from heartbeat payloads.
The legacy and current producers need the same resource owner; copying the
existing scheduler into the new producer would duplicate cleanup and leak risks.

## Scope

Extract a small resource boundary and delegate legacy start/stop to it. Preserve
legacy tick fields, interval behavior, handle keys and exception identity. A
failed scheduling operation releases its executor before propagating failure.
Current profile selection and progress observations remain a separate PR.

Adversarial review also fixed cancellation failure skipping executor shutdown.
Ordinary scheduling failures retain their cause if cleanup also fails; fatal
causes retain priority. Stop remains nil-returning and safe to repeat.

## Verification and deployment

Test cancellation, repeated/nil stop, and allocation/scheduling failure cleanup
without sleeps. Run existing heartbeat tests and all consumer projects serially,
rebuild and test the isolated package. Normal budgets, signed hooks, adversarial
standards review, fresh review and all CI gates apply. Independent resource
foundation for the approved heartbeat contract (#2072).

Four consumer projects passed serially (1m58s); rebuilt CLI is 39,203,489 bytes. Isolated
package tests: 10 tests, 30 assertions. Additional JVM-only executor fault tests
passed (one test, six assertions) within all four consumer suites; Babashka
does not support the required executor proxy. The final run includes the required
Slingshot boundary forms. Lint, inferred strata and standards checks passed.

## Review hardening

Scheduling and shutdown now catch all Slingshot values while preserving the
original Throwable wrapper. A shared cleanup boundary governs both failed start
and stop: always attempt cleanup, preserve fatal priority, and suppress ordinary
secondary failures without introducing cause cycles. This addresses all three
review findings, including fatal cancellation masked by shutdown failure.

Fresh four-consumer run: 1m38s, including two JVM cleanup tests/24 assertions per
project. Fresh CLI: 39,204,243 bytes. Isolated package: 10 tests/32 assertions.
