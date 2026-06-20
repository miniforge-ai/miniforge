# Fix Policy Pack Custom Detector Registration

## Overview

This PR removes production `resolve` use from policy-pack custom detector
binding. Custom detector symbols remain data in policy-pack manifests, but they
now bind through an explicit registry instead of ambient namespace state.

## Layer

Policy-pack extension boundary cleanup.

## Motivation

Policy compilation must not silently depend on whether a namespace happened to
be loaded before `resolve` runs. A compiled policy rule either binds to an
explicit detector mechanism or routes to the semantic detector. This PR keeps
that behavior while making the custom detector extension point deliberate and
testable.

## Changes in Detail

- Added `register-custom-fn!` / `unregister-custom-fn!` to
  `policy_pack.detection`.
- Replaced raw `resolve` for `:custom-fn` with lookup in the explicit custom
  detector registry.
- Exported custom detector registration through `policy-pack.interface`.
- Registered the built-in knowledge-safety custom detector functions at
  namespace load.
- Updated policy-pack tests to register their test custom detector symbols
  explicitly.

## Gap Analysis

### Fixed in this PR

- `components/policy-pack/.../detection.clj`
  - Removed production raw `resolve` from custom detector binding.

### Intentionally left in place

- Semantic detector injection still remains context-driven. That is the
  existing dependency-cycle boundary between policy-pack and semantic-analyzer.
- Rich-comment examples and test-only reflective assertions are outside this
  production cleanup slice.

## Testing Plan

- [x] `git diff --check`
- [x] Focused Clojure lint for touched policy-pack source and tests.
- [x] Focused policy-pack tests:

  ```bash
  clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}' -M:dev:test -m cognitect.test-runner -d components/policy-pack/test -n ai.miniforge.policy-pack.knowledge-safety-test -n ai.miniforge.policy-pack.detection-test -n ai.miniforge.policy-pack.compiler-test
  ```

- [x] `bb pre-commit`

## Deployment Plan

Merge normally after CI and review. Custom detector owners should register
their detector symbols through `policy-pack.interface/register-custom-fn!`.

## Rollback Plan

Revert the PR. The change is localized to policy-pack custom detector binding.

## Related Issues/PRs

- Builds on the standards remediation series through #1232.

## Checklist

- [x] Production raw resolver reviewed
- [x] Extension boundary made explicit
- [x] Built-in custom detectors registered
- [x] Focused tests passed
- [x] Pre-commit passed
- [ ] PR opened
- [ ] Copilot comments settled
- [ ] All review comments resolved
