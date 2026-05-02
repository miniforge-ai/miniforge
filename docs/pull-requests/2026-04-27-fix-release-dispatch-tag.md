<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
## Summary

Fix the release workflow so manual reruns update the requested release tag
instead of publishing to the dispatch ref name.

## Problem

Manual `Release` workflow dispatches were using `github.ref_name` for
`tag_name`. When dispatched from `main`, the release job updated the `main`
release instead of `v<version>`, so reruns did not attach rebuilt assets to
the intended versioned release.

## Changes

- compute an explicit `release_tag` in [.github/workflows/release.yml](/.github/workflows/release.yml)
- use `v<version>` for `workflow_dispatch`
- use `GITHUB_REF_NAME` for tag-push releases
- pass the computed `release_tag` to `softprops/action-gh-release`

## Validation

- inspected the failed rerun logs and confirmed the old workflow was publishing
  to `https://github.com/miniforge-ai/miniforge/releases/tag/main`
- reviewed the workflow diff to verify `workflow_dispatch` now targets
  `v<version>` explicitly
- `bb pre-commit`
