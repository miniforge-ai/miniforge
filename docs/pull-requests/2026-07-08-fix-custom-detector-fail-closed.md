<!--
  Title: Custom detector resolution fails closed
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: custom detector resolution fails closed

Branch: `fix/custom-detector-fail-closed`

## Summary

A `:custom` rule that declares a `:custom-fn` symbol which does not resolve used
to bind — silently — to `:semantic` (the LLM judge), indistinguishable from a
rule that intentionally has no detector. That is the #1381 failure mode as a class: a
broken registration silently degrades to the judge instead of failing loud.
`resolve-detector` now binds such a rule to `:none`, so `compile-pack` names it
as unbindable (an `:invalid-input` anomaly) — mirroring the existing
unregistered-`:capability` behavior. A rule with NO `:custom-fn` still binds to
`:semantic` (intentional).

## Reliable load-time registration

The guard only works if registration is reliable at resolution time. `#1381`
had to register the deployment detectors *lazily* because `register-custom-fn!`
validated arity via JVM reflection, which throws under babashka/SCI. Root-cause
fix: `detector-predicate?` now accepts a fn when reflection can't introspect it
(babashka), so registration works at load time on both runtimes. The deployment
detectors register at namespace load again (the lazy `delay` workaround is
gone).

## Changes

- `detection.clj`: `detector-predicate?` is babashka-tolerant.
- `compiler.clj`: `resolve-detector` binds a declared-but-unresolvable
  `:custom-fn` to `:none`.
- `phase-deployment/policy.clj`: register detectors at load (removed the lazy
  `detectors-registered` delay).

## Test plan

- `resolve-detector` unresolvable-custom-fn case updated to `:none`; new
  `compile-pack-broken-custom-fn-fails-loud-test`.
- `bb test:graalvm` passes (load-time registration under babashka).
- policy-pack + phase-deployment + gate suites: 68 tests, 589 assertions, 0
  failures. `bb poly:check` clean.
