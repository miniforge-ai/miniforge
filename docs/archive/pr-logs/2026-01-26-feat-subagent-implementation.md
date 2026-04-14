<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: split unit and integration tests

## Overview

Move integration-style tests out of brick test suites into project-level tests so
commit hooks run only unit tests, while CI continues to cover integration behavior.

## Motivation

Brick tests are executed by default in pre-commit hooks. Some integration-style
tests (git worktrees, Datalevin) cause local failures and complect unit and
integration checks. Splitting restores fast, reliable unit tests in hooks while
keeping integration coverage in CI.

## Changes in Detail

- Move integration tests from brick test namespaces into `projects/miniforge/test` namespaces.
- Replace integration-heavy brick tests with unit-focused versions.
- Add a project-level integration test runner task and adjust `test:all` to include it.
- Update CI to run the full suite and add missing project test deps (observer component).

## Testing Plan

- `bb test`
- `bb test:integration`
- `bb test:all`

## Deployment Plan

No deployment steps; changes are test-only.

## Related Issues/PRs

- N/A

## Checklist

- [ ] Verify unit tests remain fast in pre-commit
- [ ] Confirm integration suite runs in CI
- [ ] Ensure new project test deps are acceptable for the CLI project
