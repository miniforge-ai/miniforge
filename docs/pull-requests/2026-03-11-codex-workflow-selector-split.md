# PR: Move workflow selector mappings into app-owned resources

## Summary

This PR removes hardcoded software-factory workflow ids from the shared CLI
workflow selector and recommender fallback path.

The selector heuristics still live in the CLI, but they now produce logical
selection profiles:

- `:comprehensive`
- `:fast`
- `:default`

Those profiles are resolved to concrete workflow ids through app-owned classpath
resources provided by `workflow-software-factory`.

## Changes

### New config seam

- Added `ai.miniforge.cli.workflow-selection-config`
- Added resource config at:
  - `components/workflow-software-factory/resources/config/workflow/selection-profiles.edn`

Current software-factory mapping is:

- `:comprehensive -> :canonical-sdlc-v1`
- `:fast -> :lean-sdlc-v1`
- `:default -> :lean-sdlc-v1`

### Selector refactor

- `ai.miniforge.cli.workflow-selector` no longer hardcodes SDLC workflow ids
- Rule logic now selects logical profiles, then resolves them through config
- Selection results now include `:selection-profile` alongside `:workflow-type`

### Recommender fallback

- `ai.miniforge.cli.workflow-recommender` no longer hardcodes `:standard-sdlc`
- Fallback now resolves the `:default` selection profile against available
  workflows
- Prompt copy was generalized from "agentic SDLC platform" to
  "governed autonomous workflow platform"

## Why

After splitting workflow families and phase implementations into app-owned
components, the next remaining leak was the selector path in the CLI.

This keeps the heuristic engine reusable while moving the app-specific workflow
identity into resource configuration, which matches the project’s config-in-code
pattern better than hardcoded ids.

## Test coverage

Existing selector behavior coverage remains in:

- `ai.miniforge.cli.workflow-selector-test`

New seam-specific coverage was added in:

- `ai.miniforge.cli.workflow-selection-config-test`
  - resource-driven profile mapping
  - profile resolution against available workflows
- `ai.miniforge.cli.workflow-recommender-test`
  - task-type match path
  - default-profile fallback path

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-selector-test
  'ai.miniforge.cli.workflow-selection-config-test 'ai.miniforge.cli.workflow-recommender-test) ..."`
- `bb test`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`

## Follow-up

The next seam on this track is the remaining software-factory assumptions in
shared chain/recommendation helpers, not the selector mapping itself.
