# feat: compile policy-pack rules into executable checks

## Overview

This PR adds the policy-pack compiler layer that turns enabled rules into
executable N4-style check functions. It is the foundation for later phase
wiring: no enabled rule should silently disappear because it lacks a detector.

## Layer

Domain / application compiler foundation.

## Motivation

The standards pack can compile rule data today, but many enabled rules do not
bind to executable checks. That leaves policy as prompt guidance rather than an
enforced contract. The compiler must either produce a check function or fail
loudly with the rule id and missing binding reason.

## Changes in Detail

- Add a rule-to-check compiler in `policy-pack`.
- Bind pattern/content rules to deterministic artifact content scanning across
  N4 artifact collections.
- Bind mechanical rules through explicit policy capabilities.
- Bind heuristic custom rules to an injectable semantic checker and fail loud
  when judge wiring is absent.
- Preserve rule severity on produced violations.
- Register `:format` as a fail-loud mechanical capability that requires an
  injected pure formatter check or expected formatted content.
- Add regression coverage for successful compilation, clean/violating
  artifacts, capability binding, semantic binding, and unbindable rules.

## Testing Plan

- [x] Focused lint for touched Clojure files
- [x] Focused policy-pack/gate compiler tests
- [x] Broader policy-pack and gate tests
- [x] `git diff --check`
- [x] `bb pre-commit`

## Deployment Plan

Merge normally after CI and review. This is additive compiler functionality; the
follow-up PR wires compiled checks into verify/review execution.

## Rollback Plan

Revert the PR. Existing policy-pack callers continue using their current paths
until the follow-up phase wiring lands.

## Related Issues/PRs

- Spec: `work/policy-gate-compiler.spec.edn`
- Follow-up: `work/policy-gate-phase-wiring-and-evidence.spec.edn`

## Checklist

- [x] Enabled rules compile to concrete check functions or explicit errors
- [x] Capability rules are represented as policy bindings
- [x] Semantic fallback is explicit and injectable
- [x] Tests cover pass, fail, capability, semantic, and unbindable paths
- [ ] PR opened
- [ ] Copilot comments settled
- [ ] All review comments resolved
