<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N7 OPSV governance gates

## Summary

Implements the six fail-closed N4 section 5.1.5 OPSV gates and replaces the
obsolete four-phase OPSV policy metadata with the canonical seven-phase
lifecycle. Every gate failure carries a typed reason plus machine-readable
remediation whose human message and summary come from the gate catalog.

The completed observability-governance work specification is removed so future
agents do not attempt to reimplement its already-satisfied event, evidence,
gate, or policy-pack acceptance criteria. Its dependency edges and live work
inventory are removed as well, and the generated work queue is refreshed.

## Design

The implementation follows two component-local boundaries:

- `gate.opsv.core` evaluates instrumentation, environment, blast-radius,
  abort, actuation, and evidence requirements as data.
- `gate.opsv` registers those checks under the six N4 policy rule identifiers.

The split is architectural, not cosmetic. The first implementation exposed five
same-file strata; the repository stratum linter rejected it, so evaluation and
registry wiring were decomposed into separate namespaces. Gate evaluation does
not depend on the OPSV component, avoiding a reverse dependency from the shared
governance layer into the product-domain layer.

## Policy behavior

- Instrumentation requires every declared signal to be available and reliable.
- Environment policy requires allowlisting, an open time window, and explicit
  production authorization.
- Blast radius enforces replica, node, and namespace limits.
- Abort policy requires error-budget burn, saturation, and tail-latency limits.
- `APPLY_ALLOWED` remains disabled by default and requires every target service
  on the apply allowlist.
- Actuation evidence requires the Experiment Pack hash, environment fingerprint,
  and metric-snapshot references.

The policy pack now targets `discover`, `plan`, `execute`, `verify`, and
`actuate` at the appropriate gates; its vocabulary contains no obsolete SDLC
or observe-plan-stabilize-verify phases.

## Standards audit

- Localized all emitted gate descriptions, failures, and remediation summaries.
- Returned anomalies as gate-result data; no exception control flow was added.
- Decomposed the implementation when stratified-design lint found five layers.
- Added test paths during implementation and removed the completed work item.
- Kept every commit below the 200-reportable-line limit.

## Verification

- `clojure -M:poly test brick:gate`
- `clojure -M:poly test brick:policy-pack`
- `bb pre-commit`

The focused gate suite covers all six passing paths, typed remediation for all
six failing paths, explicit production authorization, every blast-radius axis,
safe non-apply actuation, and each required evidence field. The existing
workspace warnings remain unchanged; there are no Poly errors or Clojure lint
warnings.
