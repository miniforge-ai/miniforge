# PR: Move workflow recommendation prompt vocabulary into app config

## Summary

This PR moves software-factory-specific workflow recommendation prompt
vocabulary out of shared CLI code and into app-owned resources.

The recommender still assembles prompts generically, but the active classpath
now provides the workflow-summary labels and analysis dimensions.

## Changes

- Added `ai.miniforge.cli.workflow-recommendation-config`
- Added app-owned prompt config at:
  - `components/workflow-software-factory/resources/config/workflow/recommendation-prompt.edn`
- Updated `ai.miniforge.cli.workflow-recommender` to read:
  - workflow summary labels from config
  - analysis dimensions from config

## Why

After removing workflow defaults and chain resources from shared code, the next
remaining software-factory leak was the recommendation prompt itself.

Shared CLI code still hardcoded software-delivery task vocabulary such as
`feature`, `bugfix`, `refactor`, and `test`, plus software-factory summary
labels like `Includes code review`.

This keeps recommendation assembly generic while letting each composed app
define its own prompt vocabulary.

## Test coverage

New coverage in:

- `ai.miniforge.cli.workflow-recommendation-config-test`
  - software-factory prompt config loads from resources
- `ai.miniforge.cli.workflow-recommender-test`
  - configured prompt vocabulary appears in assembled prompts
  - generic fallback vocabulary is used when no app config is present

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-recommendation-config-test
  'ai.miniforge.cli.workflow-recommender-test) ..."`
- `bb test`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`

## Follow-up

The next seam on this track is the remaining software-factory naming and help
copy in shared CLI and workflow-facing docs, not the recommendation prompt
contract itself.
