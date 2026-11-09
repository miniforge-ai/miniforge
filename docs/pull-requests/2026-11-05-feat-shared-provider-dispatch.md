<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Share one-shot provider dispatch through the effect layer

## Overview

Effect-boundary coordination, extracted from the existing GitHub provider.
The native Kubernetes provider needs the same synchronous, one-attempt protection.
This is a shared mechanism, not new effect authority or experiment activation.

## Motivation

Do not duplicate dispatch state, thread ownership, escaped-thunk closure or result retention across providers.
Keep GitHub's current behavior and localized diagnostics while exposing a reusable effect-layer boundary.

## Changes in Detail

Move the existing one-shot dispatch mechanism to effect-transaction.
The provider supplies localized diagnostic messages; the shared boundary validates its inputs.
Confirmed provider reports remain authoritative despite a dispatch callback's later behavior.
Malformed, repeated, cross-thread or escaped attempts cannot produce another provider call.
Retain interruption handling, refusal semantics and explicit uncertain outcomes.
Remove the old unused private implementation after migrating its real consumer.

## Testing Plan

Regress the real GitHub creation path and add focused shared-boundary tests.
Trace defaults, each refusal, callback errors, thread ownership and result retention.
Run strict lint, true inferred strata, affected products, package and signed hooks.

Current-main validation passes:

- Strict lint across both complete components: zero warnings or errors.
- All seven changed namespaces infer to at most three strata, with no findings.
- Workspace composition and effect-transaction tests pass in all four products.
- GitHub provider component tests pass, including the real creation and command-timeout paths.
- Shared-boundary and unchanged GitHub creation regressions: 21 tests, 101 assertions.
- All 24 safe OPSV/workflow/N6 JVM integration suites: 113 tests, 1,340 assertions.
- All task entrypoints load, and a fresh CLI artifact builds.
- Isolated Babashka artifact, test paths only: 21 tests, 101 assertions.
- Native packaged compatibility regressions: 308 tests, 1,903 assertions.
- Seven deliberately broken guards cause assertion failures, each with zero errors.

The first full run hit a sandbox denial in an existing process-termination test.
The serialized unrestricted rerun passes the same test without code changes or skips.
An interrupt test now captures the flag before assertions, which may themselves clear it.
A direct probe confirmed the shared boundary already restored the flag correctly.
Review found that the custom-message fixture used the same text as the defaults.
Distinct localized values now prove both unconfirmed and malformed-callback diagnostics survive.
Deliberately discarding either provider diagnostic produces assertion failures with zero errors.
The full validation and fresh packaged tests pass again with this stronger coverage.

The owner traced the whole diff against the pinned Miniforge standards.
One diagnostics factory replaces duplicated message-map shapes.
The dependency remains one-way from provider to effect layer, with no new dependencies.
Repeated, escaped and cross-thread thunks cannot issue another provider attempt.
Callback fabrication cannot establish success, and a confirmed operation report survives later callback errors.
This preserves existing GitHub behavior rather than introducing a different exception policy.
No source overlay, live network effects, signing exception or hook bypass is used.

## Deployment Plan

No live effects, new permissions or default activation. Keep every normal merge gate.

## Related Issues/PRs

N7 completion; native request #2415, preflight #2412 and issuance #2407.

## Checklist

- [x] Implement the shared boundary and owner adversarial standards pass.
- [x] Verify GitHub compatibility and packaged composition.
- [ ] Settle full current-head review, CI and owner merge gates.
