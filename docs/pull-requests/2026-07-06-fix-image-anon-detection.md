<!--
  Title: Detection FN fixes — untagged images + reader anon fns
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: untagged-image + reader-anon-fn detection (Finding 7)

Branch: `fix/image-anon-detection`

## Summary

Two false-negative fixes from the Finding-7 catalogue.

- `k8s/no-latest-tag`: added untagged images (`image: nginx`, implicitly
  `:latest` — the common case the old `:latest`-only pattern missed) and quoted
  `:latest`; fixed the `:latest-stable` false positive. Digest-pinned
  (`@sha256`) and tagged images are left alone.
- `foundations/no-inline-anon-fns`: added the reader `#(...)` form (the more
  common inline anon fn), which the `(fn [...])`-only pattern missed.

## Test plan

- New `no-latest-tag-detection-test` and `no-inline-anon-fns-detection-test`
  pin the fire/no-fire cases (untagged, registry-port, tagged, digest,
  latest-stable; `(fn [..])`, `#(..)`, named fns).
- `standard-packs`: 7 tests, 380 assertions, 0 failures.

## Related

- Governance audit Finding 7 (`docs/detection-quality-audit-2026-07-06.md`).
