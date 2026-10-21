<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: interpret substantive heartbeat progress explicitly

## Scope

Consume the approved N3 heartbeat contract without confusing scheduler liveness
with actual progress. Current unknown coverage is explicitly unavailable; current
known coverage uses its monotonic progress gap. Preserve legacy liveness-gap
interpretation and reject unsupported versions instead of guessing their fields.

Depends on observer decomposition (#2076). This does not register or enable
current-journal `observer/alert-fired` events. Public observer values and pure
rule evaluation provide the interpretation for downstream consumers. The existing
subscriber retains current-profile alerts as bounded latest-per-rule observer
state, preserving legacy event publication. Unsupported versions retain an explicit
skip record and position; unknown coverage remains visible in that state.

## Verification plan

Test known, unknown, unsupported and legacy payloads; prove repeated ticks do
not reset progress-based stall alerts. Use canonical current-journal heartbeat
publication for acceptance coverage. Retain all standards, package, review, CI,
normal-budget and signing gates.

## Verified results

All three consuming projects passed serially (50 seconds). Fresh CLI:
39,214,205 bytes. Isolated packaged tests: 14 tests, 66 assertions. Acceptance
uses the actual alert subscriber with canonical journal publication, not a mocked
delivery path; repeated heartbeats preserve increasing progress gaps and unknown
coverage stays explicit. Legacy subscriber publication remains covered.

Adversarial review checked unsupported-major skip recording and position handling,
absence of unregistered current alert publication, shared alert factories, bounded
latest-per-rule storage and dependency direction. This is a scoped observer
integration, not a claim that N7 or workflow progress instrumentation is complete.
