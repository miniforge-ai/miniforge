<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# spec: require a kustomize-readable deploy preflight basis

## Overview

Amends `work/ariadne-deploy-grant-enforcement.spec.edn` so the queued
enforcement work cannot satisfy its acceptance criteria with a gate that
approves every manifest. Spec-only; no source or behavior changes.

## Motivation

The spec directs the enforcement work to reuse `check-resource-count` and
`check-gke-node-limit` as the preflight basis. Both parse Pulumi preview JSON:
`policy/parse-preview` calls `json/parse-string` and swallows the exception to
`{}`. Against a kustomize render there are no steps, so there are no violations,
so `deploy-authority/preflight` returns `:allow` for any manifest.

Measured on `1eab1888b`:

```text
preflight over 5000-doc kustomize render => #:preflight{:result :allow, :violations []}
```

`deploy-governed/transact!` does call both checks before the apply. The two
criteria that read "check-X is evaluated before the real apply" were therefore
satisfiable by a gate structurally unable to deny — evaluation recorded, no
decision made. That is worse than an absent gate, because the durable effect
record attests to a check that could not have failed.

Kustomize is the primary Kubernetes provisioning path here, so the checks become
kustomize-aware rather than the deploy seam growing a separate basis.

## Layer

Work spec (no component code).

## Changes in Detail

- Record the vacuous-preflight finding with its measurement in
  `:spec/description`.
- Replace the two evaluation-only criteria with denial criteria: each check must
  deny a kustomize render that exceeds its limit.
- Require a test per remaining preflight check asserting a violation over a
  kustomize render, not only over Pulumi preview JSON.
- Add constraints that every check parses the artifact the configured provider
  renders, and that a check unable to return a violation is not left in the
  preflight path.
- Record the provider-shape mismatches found while checking whether #1713's
  operations satisfy `transact!`: `dry-run!` is 2-arity against a 1-arity call
  site, and `apply!` returns a schema-shaped map with no `:deploy/failed?`, so a
  failed apply reads falsy in `commit!` and never records
  `:effect/outcome :failed`. `:observe!` and `:rollback-info!` already match.
- Add criteria covering both, plus byte-identity between the proposal's recorded
  manifest and the bytes applied — the reason #1713 added `render!` /
  `apply-rendered!` / `dry-run!`.

Two sub-decisions are named as belonging to the implementation, not fixed here:
whether to add a YAML reader (phase-deployment has cheshire and malli only) or
split documents structurally; and what `check-gke-node-limit` means over a
kustomize render, given GKE node pools are infrastructure rather than manifest
resources.

## Testing Plan

- `spec-parser` validates the amended file: `parse-spec-file` ok,
  `valid-spec-payload?` true, `validate-spec` `{:valid? true}`.
- `work/QUEUE.md` row is derived from title, tier, theme, and axes, all
  unchanged — no regeneration needed.
- No source changes, so no component suite is affected.

## Deployment Plan

None. Spec text only.

## Related Issues/PRs

- #1713 — exact deployment preflight operations (merged); its `render!`,
  `apply-rendered!`, and `dry-run!` are the operations this spec now requires the
  enforcement work to bind correctly.
- #1752 — the deploy governance seam this spec finishes wiring.

## Checklist

- [x] Preflight criteria require a demonstrated denial, not an evaluation.
- [x] Provider-shape mismatches recorded with their failure mode.
- [x] Amended spec validates against the spec-parser schema.
- [x] No behavior change.
