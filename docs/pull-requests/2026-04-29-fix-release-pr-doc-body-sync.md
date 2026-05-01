<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix/release-pr-doc-body-sync

## Summary

Fixes the release-executor seam that was causing Miniforge-generated PRs to
open with thin generic bodies even when a richer PR doc had already been
generated.

The root cause was split metadata authority:

- `:release/pr-description` was storing a fully rendered fallback body instead
  of a plain summary string
- PR creation used `:release/pr-description` instead of the richer
  `:release/pr-body`
- PR body updates rebuilt a minimal template instead of reusing the generated
  PR doc content

This PR restores the intended contract so Miniforge-generated PRs carry the
full rich description and the short summary remains a summary.

## Key Changes

- makes PR creation use `:release/pr-body` in
  [core.clj](../../components/release-executor/src/ai/miniforge/release_executor/core.clj)
- makes PR body updates prefer generated doc content, then the rich body, then
  a full renderer fallback in
  [core.clj](../../components/release-executor/src/ai/miniforge/release_executor/core.clj)
- persists generated PR doc content into pipeline state so later steps reuse
  the exact rich markdown in
  [core.clj](../../components/release-executor/src/ai/miniforge/release_executor/core.clj)
- restores `:release/pr-description` to a short summary string while keeping
  `:release/pr-body` as the rich markdown body in
  [metadata.clj](../../components/release-executor/src/ai/miniforge/release_executor/metadata.clj)

## Tests

- adds PR creation coverage proving the rich body is used in
  [core_pipeline_test.clj](../../components/release-executor/test/ai/miniforge/release_executor/core_pipeline_test.clj)
- updates metadata coverage to assert summary/body separation in
  [metadata_comprehensive_test.clj](../../components/release-executor/test/ai/miniforge/release_executor/metadata_comprehensive_test.clj)
- adds PR body update coverage proving generated doc content wins over the
  fallback body in
  [pr_body_test.clj](../../components/release-executor/test/ai/miniforge/release_executor/pr_body_test.clj)

## Validation

- `clj-kondo --lint` on touched release-executor files
- `bb test components/release-executor`
- `bb pre-commit`

## Outcome

After this change, Miniforge PRs should open and update with the same rich
content that is written to `docs/pull-requests/...`, instead of falling back to
the thin generic template.
