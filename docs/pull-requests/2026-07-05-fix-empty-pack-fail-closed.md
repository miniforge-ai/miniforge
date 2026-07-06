<!--
  Title: Deploy policy gate fails closed on empty packs
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: deploy policy gate fails closed on empty packs

Branch: `fix/empty-pack-fail-closed`

## Summary

Closes governance-audit Finding 5 (`miniforge-governance-audit-2026-07-05.md`).
The deployment `:policy-pack` gate (`gate/policy.clj`) passed clean when no
policy packs were loaded. Decision: fail closed on the deploy path — zero
deploy policy is a misconfiguration, not an allowance.

## Behavior change (read this)

This is not a one-line posture flip. Investigation showed the `:policy-pack`
gate at provision was a **complete no-op**: nothing ever populated ctx
`:policy-packs` at runtime (the config's `:policy-packs` is a different,
config-shaped key), so the gate always saw `[]` and passed. And the
`deployment-safety` pack it was meant to run had never been loaded, so it had
accumulated latent bugs.

So a correct fail-closed required **activating deployment policy enforcement for
the first time**:

- `gate/policy.clj`: empty packs now block (`{:passed? false :errors
  [{:type :no-policy-packs}]}`). The phase-scoped SDLC gate keeps its softer
  skip-on-no-packs posture (a repo without a standards pack is allowed there);
  the deploy gate does not.
- `phase-deployment/policy.clj`: loads the shipped `deployment-safety` pack and
  supplies it into the provision phase's gate ctx (`provision.clj` enter). Empty
  only when the pack resource is absent → gate fails closed.
- `deployment-safety/pack.edn` bug fixes (the pack had never been evaluated):
  - `:rule/enforcement :action :block` → `:hard-halt` (2 rules). `:block` is
    not a valid enforcement action, so post-PR1 the "block destructive
    production ops" and "block secrets in manifests" rules classified as nothing
    and would not have blocked.
  - `:check-fn 'sym` → `:check-fn sym` (2 rules): the leading quote made
    `clojure.edn` read the symbol with the apostrophe baked into the namespace.

Net effect: a destructive Pulumi preview (delete/replace) now blocks the
provision gate; a public-endpoint preview requires approval; a benign preview
passes.

## Out of scope (noted, not fixed here)

- The two `:custom` `:warn` rules (resource-count, gke-node-limit) use
  `:check-fn` where the resolver expects `:custom-fn`, and their detector fns are
  unregistered — so they route to the (unwired, no-op) semantic judge. They are
  non-blocking and are detection-quality work (Finding 7). Left as-is (current
  behavior; verified they no-op without error).
- The pack's `:high`/`:medium`/`:critical` severities fail the current 4-level
  pack schema enum (`valid-pack?` is false). Severity is advisory post-PR1 and
  `check-artifact` does not validate, so gating is unaffected. These become
  valid under Finding 6's 5-level canonical enum; migrated there.
- The `:deploy`-phase rule (`no-secrets-in-manifests`) needs the `:policy-pack`
  gate added to the deploy phase's gates to run; the deploy phase currently
  gates only `[:deploy-healthy]`. Separate gap.

## Test plan

- gate + phase-deployment suites: 88 tests, 229 assertions, 0 failures.
- New: deploy gate fails closed on empty/absent packs; `deployment-policy-packs`
  supplies the pack; the pack blocks a destructive preview and passes a benign
  one (via `check-artifact`, the real gate path).
- `bb poly:check` clean.

## Related

- Governance audit Finding 5. Finding 7 (detection quality) will address the
  custom-fn warn rules; Finding 6 the severities.
