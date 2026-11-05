<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: construct workflow recovery events

## Plan and scope

Event-stream foundation on main 3b8a3855c, following merged typed admission #2069.
Expose constructors for the six existing N3 §3.21 recovery facts through the
canonical interface. Reuse workflow-envelope profile selection, shared failure
fields, localized messages and existing contract fixtures. Construction must not
publish, reserve a current-journal sequence, or synthesize missing required facts.
No heartbeat amendment, store migration, default-journal enablement or new
normative event type.

## Verification plan

Exercise all public constructors against real current journals, exact payloads,
message interpolation, sequence-free drafts, durable receipts, replay and duplicate
publication. Check legacy envelope compatibility and allocation-anomaly propagation.
Register all constructors for the existing event-type audit. Run all four consuming
projects serially, rebuild the CLI, and run isolated packaged tests.
Require normal signed hooks, inferred strata, actual-base standards scan,
exact-head review and all CI.

## Verified implementation

All four consuming projects passed serially (1m33s); the rebuilt CLI is
39,200,608 bytes. Isolated packaged constructor, admission, registry, lifecycle
and envelope regressions passed 21 tests / 734 assertions with test fixtures
only. The focused constructor suite passed 3 tests / 70 assertions. Kondo
reported zero warnings/errors; inferred namespaces stay within three strata.
Adversarial review checked public aliases, exact field mapping, failure-field
selection, profile selection, allocation-anomaly identity, localized placeholders
and sequence ownership. Constructors deliberately do not publish or change the
checkpoint store.

## Deployment

Additive constructor API only. Actual checkpoint/resume producer integration is
the next slice; this PR alone does not satisfy N2D.CK.5 or establish N7 completion.
