# refactor: standardize spec EDN on `:spec/*` keywords

## Layer

Domain Model + Application

## Depends on

- None

## Overview

Migrate all spec EDN files and their consumers from a mixed bag of `:task/*`,
unnamespaced, and `:spec/*` keywords to a single canonical `:spec/*` namespace.
This enforces the bounded-context boundary between Spec (user intent), Task
(system work units), and Workflow (execution process).

## Motivation

Spec files evolved organically and accumulated three different keyword styles:

| Style | Example | Origin |
|-------|---------|--------|
| Unnamespaced | `:title`, `:description` | Earliest specs |
| `:task/*` | `:task/title`, `:task/description` | Mid-era confusion between spec and task |
| `:spec/*` | `:spec/title`, `:spec/description` | Correct canonical form |

Consumers papered over this with fallback chains (`(or (:spec/title spec) (:task/title spec) (:title spec))`),
and several dug into `:spec/raw-data` to fish out operational keys like `:repo-url` and `:llm-backend`.
This made the normalizer the wrong shape and pushed complexity downstream.

## What this changes

### 1. Normalizer rewrite (`spec_parser.clj`)

The `normalize-spec` function now:

- Accepts all three input formats via triple-or extraction (`:spec/*` > `:task/*` > unnamespaced)
- Promotes operational keys (`:repo-url`, `:branch`, `:llm-backend`, `:sandbox`,
  `:plan-tasks`, `:acceptance-criteria`, `:code-artifact`) to first-class `:spec/*` keys
- Emits a deprecation warning on stderr when `:task/*` keys are detected
- Preserves `:spec/raw-data` for any consumer that still needs the original map

### 2. Consumer simplification

Removed `(get-in spec [:spec/raw-data ...])` patterns from:

- `workflow_runner.clj` — sandbox flag
- `sandbox.clj` — repo-url, branch
- `context.clj` — llm-backend, plus all promoted keys in `spec->workflow-input`
- `workflow_selector.clj` — simplified fallback chains for type/description/constraints
- `workflow_recommender.clj` — removed title fallbacks

### 3. Dashboard backward compatibility

- `archive.clj` and `state/workflows.clj` — added `:spec/title` as primary lookup
  while keeping existing fallbacks for archived event data

### 4. EDN file migration

All 16 example and 20 work spec files migrated:

- Top-level `:task/*` keys renamed to `:spec/*`
- Top-level unnamespaced keys (`:title`, `:description`, etc.) renamed to `:spec/*`
- Free-form context keys (`:diagnostic-steps`, `:common-fixes`, etc.) left unnamespaced
- `:task/*` keys inside `:plan/tasks` entries preserved (they ARE tasks)
- `:workflow/*` and `:plan/tasks` keys preserved as-is

### 5. Tests

New `spec_parser_test.clj` covering:

- Canonical `:spec/*` format pass-through
- Transitional `:task/*` format migration
- Legacy unnamespaced format migration
- `:plan/tasks` promotion
- Priority ordering (`:spec/*` wins over `:task/*` wins over unnamespaced)
- Validation (missing title/description throws)
- Default values for optional fields

### 6. Documentation

Rewrote `examples/workflows/README.md` with canonical format reference,
domain namespace table, and migration guide.

## Changes in Detail

- `bases/cli/src/ai/miniforge/cli/spec_parser.clj` — normalizer rewrite + deprecation warning
- `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` — use `:spec/sandbox`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/sandbox.clj` — use `:spec/repo-url`, `:spec/branch`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/context.clj` — use promoted `:spec/*` keys
- `bases/cli/src/ai/miniforge/cli/workflow_selector.clj` — simplified extraction
- `bases/cli/src/ai/miniforge/cli/workflow_recommender.clj` — simplified title lookup
- `components/web-dashboard/src/ai/miniforge/web_dashboard/archive.clj` — `:spec/title` lookup
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj` — `:spec/title` lookup
- `bases/cli/test/ai/miniforge/cli/spec_parser_test.clj` — **new** test file
- `examples/workflows/README.md` — rewritten documentation
- `examples/workflows/*.edn` (16 files) — keyword migration
- `work/*.edn` + `work/archive/*.edn` (20 files) — keyword migration

## Testing Plan

- `bb pre-commit` — all checks pass (lint, format, poly test, GraalVM compat)
- New `spec_parser_test.clj` covers all three input formats and edge cases
- Existing `workflow_selector_test.clj` and `file_writing_test.clj` continue to pass unchanged
