<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix: remove with-redefs of clojure.core/requiring-resolve from gate.behavioral-test

## Overview

Eliminates a brick-isolation hazard reported by two sibling Wave 2 PRs (#702 response-chain, #704 foundation cleanup): under bb's brick-pmap test runner, `gate.behavioral-test` and `dag-executor.state-test` raced through a globally redefined `clojure.core/requiring-resolve`, producing intermittent failures.

The fix is dependency injection on `gate.behavioral/check-behavioral`. Six tests no longer redefine a core fn globally; they pass a stub via `ctx` instead.

## Motivation

Two-headed root cause:

1. **The dag-executor side was already fixed upstream** in merge commit `38f8f486` ("keep direct event-stream require over soft-dep resolution"). `state.clj` no longer uses `requiring-resolve` for event-stream lookup. Originally-flaky path is dormant on `main`.
2. **The actual remaining hazard was in `gate.behavioral-test`.** Six tests wrapped their bodies in `with-redefs [clojure.core/requiring-resolve …]` to stub out `policy-pack.core/check-artifact`. `with-redefs` mutates a global var root — brick isolation is irrelevant. While the scope was active, any parallel test on any brick that called `requiring-resolve` (directly or transitively, through middleware, defmulti dispatch, future code paths) received the stub and got `nil` for any sym that wasn't `policy-pack.core/check-artifact`. The dag-executor pre-fix path hit it; the same hazard remained for every other brick, even after dag-executor was cleaned up.

This PR removes the hazard entirely by replacing the global redef with a non-global mechanism.

## Base Branch

`main`

## Depends On

None.

## Layer

Bug fix / test hygiene. `gate` component.

## What This Adds / Changes

`components/gate/src/ai/miniforge/gate/behavioral.clj`:

- `check-behavioral` now reads an optional `:check-fn` from its `ctx` argument.
- Default is a new private `default-check-fn` that performs the same lazy `requiring-resolve` of `policy-pack.core/check-artifact` as before — preserving the intentional soft-dependency semantics (policy-pack is *not* on `gate/deps.edn`'s classpath in isolation; the lazy resolution is intrinsic to production behavior).
- `default-check-fn` throws a single `ex-info` at the soft-dep resolution boundary when policy-pack is genuinely absent. The existing try/catch in `check-behavioral` converts it to the same `:behavioral-check-error` warning shape as before — public contract unchanged.

`components/gate/test/ai/miniforge/gate/behavioral_test.clj`:

- Six tests that previously used `with-redefs` now construct a `ctx` with a `:check-fn` stub. No global var-root mutation. Two private test helpers (`stub-check-fn`, `throwing-check-fn`) keep the per-test bodies focused on the behavior under test.

## Why option B (DI) instead of option A (direct require)

Option A — replacing `requiring-resolve` with a top-level `:require` of `ai.miniforge.policy-pack.interface` — was infeasible. `policy-pack` is a *deliberate soft dependency* of `gate`; it is not on `gate/deps.edn`'s classpath in isolation. The lazy resolution in production is intrinsic.

Option B — dependency injection — keeps the soft-dep semantics intact while removing the global mutation. Tests now inject stubs through the call surface without touching a core fn.

## Out of scope

`policy.clj` and `lint.clj` use the same `requiring-resolve` pattern, but their tests do not currently `with-redefs` around them — no race exists today. Per the task scope, this PR fixes the one site that was actually causing flakes. Refactoring those siblings as a hygiene exercise is a follow-on.

## Strata Affected

- `ai.miniforge.gate.behavioral` — public contract preserved; new `:check-fn` opt is additive
- `ai.miniforge.gate.behavioral-test` — six tests refactored to inject via `ctx` instead of redefining a core fn

## Testing Plan

- **`bb test` — six consecutive runs, all green:** `2925 tests / 10869 passes / 0 failures / 0 errors` each iteration.
- **`bb pre-commit` green:** lint, format, full test suite, GraalVM compatibility all passed.

The race no longer reproduces under brick-pmap concurrency.

## Deployment Plan

No migration required. Public contract of `gate.behavioral/check-behavioral` is preserved — production callers do not pass `:check-fn`, so behavior is identical. The `default-check-fn` performs the same soft-dep resolution and emits the same `:behavioral-check-error` warning shape as before.

## Notes

- **Apache 2 license headers** preserved on both files.
- **Anomalies-as-data discipline:** the one `throw ex-info` in `default-check-fn` sits at the soft-dep resolution boundary and is caught by the existing try/catch wrapper in `check-behavioral`, which converts it to the warning shape. Boundary-flavored throw + immediate catch + return-as-data — consistent with the `005 exceptions-as-data` rule.
- **Per-behavior test decomposition** preserved (one deftest per scenario).

## Related Issues/PRs

- Sibling PRs that flagged the race in their reports:
  - `feat/response-chain-component` (PR #702)
  - `refactor/exceptions-as-data-foundation-cleanup` (PR #704)

## Checklist

- [x] Race no longer reproduces (6 consecutive `bb test` runs green)
- [x] Public contract of `check-behavioral` preserved
- [x] Soft-dep semantics preserved (production lazy resolve unchanged)
- [x] Six tests refactored from global `with-redefs` to per-call DI
- [x] No new throws in non-boundary code paths
- [x] Apache 2 headers on every modified file
- [x] `bb pre-commit` green
