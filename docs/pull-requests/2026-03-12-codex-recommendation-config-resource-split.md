# Recommendation Config Resource Split

## Layer

Infrastructure

## Depends on

- #307 (`codex/cli-base-split`) — merged
- #309 (`codex/test-catalog-followup`) — open

## What this adds

- removes the workflow recommendation fallback prompt config from code
- adds a base-owned default prompt resource at `config/workflow/recommendation-prompt-default.edn`
- updates the recommendation config loader to merge resource-backed defaults with app-owned overrides
- adds tests that prove both the app override prompt and the fallback prompt are now loaded from resources

## Strata affected

- `bases/cli` — workflow recommendation config loading
- CLI recommendation tests — source-of-truth assertions for default and override prompt resources

## Why

- `workflow_recommendation_config.clj` still carried configuration data in code after the app-config split
- this is the same fault line you called out earlier: prompt vocabulary is config, not logic
- leaving the fallback map in code would make later localization and product-specific prompt shaping harder than it
  needs to be

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-recommendation-config-test
  'ai.miniforge.cli.workflow-recommender-test)
  (clojure.test/run-tests 'ai.miniforge.cli.workflow-recommendation-config-test
  'ai.miniforge.cli.workflow-recommender-test)"`
