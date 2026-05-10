<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(test): wire stable-derived scope and diagnostics into bb tasks

## Overview

Wire the `bb` task surface to a stable-derived Polylith test wrapper
and add diagnostic commands for subset, expand, and bisect runs.

## Why

The repo needed two operational fixes:

- `bb test` should use the real stable-derived changed-and-affected
  project set instead of a custom opaque approximation
- when the narrowed path fails, there needs to be a first-class way to
  isolate the failing project subset quickly instead of rerunning the
  whole hook blindly

## What changed

- wire `bb test` through the stable-derived wrapper
- add `test:since-stable`, `test:since-stable:subset`,
  `test:since-stable:expand`, and `test:since-stable:bisect`
- add `scripts/test-since-stable.bb` as a thin runtime wrapper over the
  shared `bb-test-runner` planning helpers
- keep `test:all` on the full Poly unit-brick path instead of the
  narrowed stable-derived path

## Files changed

- `bb.edn`
- `scripts/test-since-stable.bb`

## Verification

- `bb test:since-stable:subset`
- `bb pre-commit`
