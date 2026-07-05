<!--
  Title: Gate merge preserves policy gates
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: gate merge preserves policy gates

Branch: `fix/additive-gate-merge`

## Summary

Closes governance-audit Finding 4 (`miniforge-governance-audit-2026-07-05.md`).

`phase/registry.clj`'s `merge-with-defaults` was `(merge defaults config)` — a
shallow merge. A workflow phase that declared `:gates [...]` replaced the
phase-type default's gate vector wholesale, silently dropping
`:policy-verify` / `:policy-review` with no error and no warning.

This was not hypothetical: `quick-fix-v2.0.0` declares `:verify :gates
[:tests-pass]`, so every quick-fix run dropped `:policy-verify` — the verify
phase ran no policy enforcement.

## Fix

`merge-with-defaults` now preserves policy gates across a `:gates` override: an
override may add or trim mechanical gates (syntax, lint, coverage, …), but any
policy gate (`policy-gates` = `#{:policy-verify :policy-review :policy-pack}`)
the phase-type default enforced is re-added if the override dropped it.

- `quick-fix-v2` verify → `[:tests-pass :policy-verify]` (policy restored, the
  lightweight mechanical set preserved).
- `canonical-sdlc-v2` implement `[:syntax :lint :no-secrets]` → unchanged (no
  policy gate in the implement default).

Chosen over blanket additive-union (the audit's other option) because union
would also force `:format` back onto canonical's implement and
`:coverage`/`:pre-verify-lint` back onto quick-fix's verify — changing author
intent for non-policy gates. Re-adding only policy gates is the precise fix.

`policy-gates` is defined in the phase registry (a small, documented data
reference to gate names, not a code dependency); the merge stays a pure data
operation, so no logging dependency is added. Re-adding is strictly safer than
the audit's "loud warning" minimum: enforcement is preserved, not merely
flagged.

## Test plan

- New `phase/registry-test`: `merge-gates` (dropped policy gate re-added; kept
  gate not duplicated; mechanical-only default returned verbatim; empty override
  still restores policy) and `merge-with-defaults` (no-`:gates` config keeps the
  full default; the quick-fix footgun is closed).
- phase-software-factory verify + verify-failure-modes + workflow
  environment-promotion integration: 32 tests, 96 assertions, 0 failures.
- `bb poly:check` clean.

## Related

- Governance audit Finding 4. `quick-fix-v2.0.0.edn` is intentionally left
  declaring `:gates [:tests-pass]` — the invariant is enforced centrally in the
  merge, so no workflow can footgun regardless of what it declares.
