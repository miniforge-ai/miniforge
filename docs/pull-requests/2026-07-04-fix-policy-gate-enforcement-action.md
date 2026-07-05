<!--
  Title: Policy + behavioral gates classify by enforcement action
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: policy + behavioral gates classify by enforcement action

Branch: `fix/policy-gate-enforcement-action`

## Summary

Closes the deployment-gate enforcement hole from the 2026-07-05 governance
audit (Findings 1 and 2). Two gates — `:policy-pack` (deployment path,
`gate/policy.clj`) and `:behavioral` (`gate/behavioral.clj`) — called
`policy-pack/check-artifact`, discarded its already-correct action-classified
buckets, and re-derived pass/fail from a severity cascade instead.

That cascade recognized `:critical/:high/:medium`, but packs declare
`:critical/:major/:minor/:info`. So `:major` and `:minor` rules matched nothing
and fell through to a silent audit pass. Content-scan violations compounded it:
they carry no `:severity` key at all, so even a `:critical` content-scan rule
passed. Net effect at deploy: a `:major` `:hard-halt` rule
(`terraform-aws/require-vpc`, `k8s/require-resource-limits`, …) recorded an
audit note and passed the gate.

The fix makes both gates consume `check-artifact`'s buckets, which are keyed off
each rule's `:rule/enforcement :action` (the SDLC gate's model) — the one signal
pack authors write. Severity becomes advisory and never gates.

## Changes in Detail

- `gate/policy.clj`: `check-policy-pack` now reads `check-artifact`'s
  `:blocking` / `:require-approval` / `:warnings` / `:audits`. `:hard-halt` and
  `:require-approval` block; `:warn` / `:audit` warn. Deleted
  `evaluate-severity-cascade`, `violation->gate-result`, and the unused
  `request-approval-for-violations!` (the only consumers of the wrong
  vocabulary), plus the now-unused `event-stream` require.
- `gate/behavioral.clj`: `check-behavioral` consumes the same buckets; dropped
  the `gate.policy` require and the `passed-cascade?` / `violation-results`
  helpers.
- Fail-closed on exception (unchanged) and blocking-on-require-approval
  (unchanged deploy posture) are preserved.

## Scope Boundary

Empty-pack fail-open on the deployment path (audit Finding 5) is left as-is here
and handled in a later PR, per the audit's suggested order.

## Verification

- `components/gate` full suite: 104 tests, 281 assertions, 0 failures.
- New regression tests (real pack + content-scan, not stubs):
  - `:major` `:hard-halt` content-scan rule → gate blocks.
  - `:major` `:warn` rule → gate passes with a warning (severity does not gate).
  - clean artifact → passes.
- Behavioral gate tests rewritten to the enforcement-action bucket shape,
  including a `:require-approval`-blocks case.

## Related

- Governance audit: `miniforge-governance-audit-2026-07-05.md` (Findings 1, 2).
