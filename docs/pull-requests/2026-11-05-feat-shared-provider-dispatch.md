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

## Deployment Plan

No live effects, new permissions or default activation. Keep every normal merge gate.

## Related Issues/PRs

N7 completion; native request #2415, preflight #2412 and issuance #2407.

## Checklist

- [ ] Implement the shared boundary and owner adversarial standards pass.
- [ ] Verify GitHub compatibility and packaged composition.
- [ ] Settle full current-head review, CI and owner merge gates.
