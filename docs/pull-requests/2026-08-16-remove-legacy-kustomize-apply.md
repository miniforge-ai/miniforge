<!--
  Title: Remove the legacy Kustomize apply shell seam
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(deploy): remove legacy Kustomize apply seam

## Layer

Deployment shell boundary.

## Overview

Remove the lower direct build-and-apply helper after the governed deployment
path made exact-byte render, server validation, and application the only live
shell protocol.

## Changes

- Delete `kustomize-apply!`, remove the unused build/apply aliases, and make
  the raw build helper private.
- Replace the former combined render/apply result schema with the focused
  `KustomizeRenderResult` shape.
- Remove the obsolete `:apply-result` field and direct-apply-only tests.
- Retain coverage for render failures, stable manifest placement, and exact
  server-dry-run/application bytes.

## Verification

- Confirm no source or test references `kustomize-apply!` or the former
  `KustomizeResult` schema.
- `clojure -M:poly test brick:phase-deployment`
- `bb pre-commit`
- `bb review`
