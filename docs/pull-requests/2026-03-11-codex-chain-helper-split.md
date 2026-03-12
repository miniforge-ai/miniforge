# PR: Remove hardcoded workflow default from resume

## Summary

This PR removes the hardcoded `:lean-sdlc` fallback from the CLI resume path.

Resumed runs now resolve their fallback workflow through the same app-owned
selection-profile seam used by workflow selection and recommendation.

## Changes

- Added `ai.miniforge.cli.main.commands.resume/resolve-resume-workflow`
- `resume-workflow` now prefers the recorded workflow spec and otherwise
  resolves the `:default` selection profile through
  `ai.miniforge.cli.workflow-selection-config`
- Added direct unit coverage for the resolver

## Why

After moving workflow selector defaults and chain resources behind app
composition, the resume path still carried a software-factory-specific fallback
workflow id in shared CLI code.

This keeps resume generic and lets each composed app decide its own default
workflow for reconstructed runs.

## Test coverage

New coverage in:

- `ai.miniforge.cli.main.commands.resume-test`
  - recorded workflow spec wins
  - app-configured default fallback is used when no spec is present
  - missing fallback produces a clear error

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main.commands.resume-test) ..."`
- `bb test`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`

## Follow-up

The next seam on this track is the remaining software-factory language and
heuristics in workflow recommendation prompts and adjacent CLI helper copy, not
the default workflow binding itself.
