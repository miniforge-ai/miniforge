<!--
  Title: Remove the legacy deployment provider apply seam
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(deploy): remove legacy provider apply seam

## Layer

Deployment provider boundary.

## Overview

Remove the direct Kustomize build-and-apply provider operation left unused
after the governed deployment transaction replaced the legacy application
flow. Governed deployment renders once, validates those exact bytes, and
applies those same bytes through `apply-rendered!`.

## Changes

- Delete the unreferenced `deploy-provider/apply!` operation.
- Update provider documentation to describe only the live render and
  exact-byte apply seams.
- Retain the lower shell implementation for separate boundary cleanup.

## Verification

- Confirm no source or test references `deploy-provider/apply!`.
- `clojure -M:poly test brick:phase-deployment`
- `bb pre-commit`
- `bb review`
