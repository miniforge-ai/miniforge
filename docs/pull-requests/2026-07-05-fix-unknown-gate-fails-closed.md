<!--
  Title: Unknown gate fails closed
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: unknown gate fails closed

Branch: `fix/unknown-gate-fails-closed`

## Summary

Closes governance-audit Finding 3 (`miniforge-governance-audit-2026-07-05.md`).

`gate/registry.clj`'s `get-gate :default` returned a pass-through check
(`{:passed? true :warnings [{:type :unknown-gate}]}`). A gate keyword that is
misspelled, renamed, or never registered passed the phase with a warning buried
in the result. In a system that sells fail-closed governance, the unknown case
is the dangerous one.

- `get-gate :default` now returns `{:passed? false :errors [{:type
  :unknown-gate}]}` — an unresolvable gate halts the phase.
- Added an explicit, registered `:noop` gate for deliberate pass-through, so
  "unknown" (fails closed) and "intentionally skipped" (`:noop`) are distinct
  and greppable.
- Added `registry/gate-registered?` — true only when a keyword resolves to its
  own `get-gate` implementation, not the fail-closed default.

## Blast radius

Every gate referenced by a shipped workflow / phase-default was cross-checked
against the registered `defmethod`s. Exactly one referenced gate did not
resolve: `:classification-gate`, referenced by
`security-compliance-v1.0.0.edn`'s `:sec-classify` phase. That gate belongs to
the separate `loop` Gate protocol (`gate-classification` component), not the
multimethod `:gates` runner; the phase's classification actually runs in its
body, and the gate reference only "worked" because the old default passed it
through silently. Changed that reference to `:noop` (deliberate skip) so the
prototype does not halt under the fail-closed default.

`deploy-healthy` / `health-check` / `provision-validated` register via
`defmethod` without `register-gate!`, so they resolve fine (they were only
absent from `list-gates`, not from dispatch).

## Resolve-check

Two tests guard the gate-name space so a future unresolved reference is caught
at CI time, not at runtime:

- `registry-test/shipped-gate-references-resolve-test` — the SDLC /
  gate-component references.
- `phase-deployment gates-test/deploy-default-gate-references-resolve-test` —
  the deploy references (that test already loads the deployment gates).

They split by ownership because the gate component's test cannot depend on
phase-deployment (backwards dependency).

## Test plan

- `components/gate` + `phase-deployment` gates: 113 tests, 322 assertions, 0
  failures.
- `workflow-security-compliance` (e2e + phases) and workflow gate-path tests:
  51 tests, 175 assertions, 0 failures.
- Updated `interface-test/get-gate-unknown-test` from the old pass-through
  assertion to fail-closed.
- `bb poly:check` clean.

## Related

- Governance audit Finding 3. Follow-up worth considering: a runtime boot-time
  resolve-check (this PR guards at CI/test time; a workflow-registration-time
  check would need the workflow layer, which knows both gates and workflows).
