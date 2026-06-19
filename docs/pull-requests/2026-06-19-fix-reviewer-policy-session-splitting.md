<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix Reviewer Policy Session Splitting

## Overview

Ensure reviewer context-window pressure never silently degrades compiled policy
application. When one reviewer prompt cannot fit the model context window, the
reviewer must split policy application across multiple LLM sessions rather than
skip the LLM review and rely only on mechanical gates. Move reviewer user
prompt assembly out of ad hoc string composition and into a Selmer-backed
resource template.

## Motivation

PR #1229 made reviewer prompt construction context-aware, but its irreducible
overflow path can skip the reviewer LLM call. That is not acceptable for
compiled policy: every compiled policy must be faithfully applied, whether the
enforcement is mechanical or LLM-mediated.

## Changes in Detail

- Add reviewer prompt chunking/session splitting for over-budget review inputs.
- Preserve mandatory LLM policy application instead of degrading to a
  mechanical-only overflow result.
- Render reviewer user prompts through Selmer templates in `reviewer.edn`.
- Add focused regression coverage for the split-review path.

## Testing Plan

- Focused reviewer tests for context splitting and reviewer prompt rendering.
- Focused Clojure lint for touched reviewer files.
- `bb pre-commit` before commit.
- GitHub CI after PR creation.

## Deployment Plan

Normal merge after review and CI. No runtime migration required.

## Related Issues/PRs

- Follows #1229.

## Checklist

- [x] Inspect reviewer budget and prompt assembly path.
- [x] Implement LLM session splitting for over-budget reviewer inputs.
- [x] Move reviewer user prompt assembly to Selmer templates.
- [x] Add regression tests.
- [x] Run focused tests.
- [x] Run `bb pre-commit`.
- [ ] Open PR and resolve review comments.
