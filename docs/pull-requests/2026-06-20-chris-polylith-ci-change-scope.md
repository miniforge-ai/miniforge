<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Polylith CI Change Scope

## Overview

Run stable-derived changed-and-affected tests on pull requests while preserving full-suite testing on `main`.

## Motivation

`bb test` already delegates to `scripts/test-since-stable.bb`, which asks Polylith for changed-or-affected projects
relative to the latest stable tag and falls back to a full sweep if no stable tag is available. CI still calls
`bb test:all` on every PR, so it is not using the change-derived test scope described by
`work/polylith-workflow-gates-and-scope.spec.edn`.

## Changes in Detail

- Update the Linux CI Test job to run `bb test` for pull requests.
- Keep `bb test:all` for `main` branch pushes.
- Preserve the existing full-history checkout so stable tags are available to Polylith.
- Remove the obsolete Linux standalone `poly` CLI install; the test runner uses `clojure -M:poly`.
- Update development guidance to describe structural checks, pre-commit smoke tests, PR scoped tests, and main full
  tests accurately.

## Testing Plan

- Validated workflow YAML and task references by inspection.
- `bb pre-commit`
- `bb test`

`actionlint` is not installed locally; GitHub Actions remains the workflow-specific syntax check.

## Deployment Plan

Merge after CI and review comments are resolved. The next PR should cover the `bases/lsp-mcp-bridge` boundary audit
from the same work spec.

## Related Issues/PRs

- Covers Group 2 of `work/polylith-workflow-gates-and-scope.spec.edn`.
- Builds on the existing `bb test` / `scripts/test-since-stable.bb` stable-derived runner.

## Checklist

- [x] Pull requests use stable-derived changed-and-affected test scope.
- [x] Main branch pushes still run the full suite.
- [x] Documentation matches the current validation model.
- [ ] CI and review comments are resolved.
