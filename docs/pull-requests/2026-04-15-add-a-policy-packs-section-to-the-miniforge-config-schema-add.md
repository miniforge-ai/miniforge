<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR #549 — Per-repo and per-org policy pack configuration

**Branch:** `mf/add-a-policy-packs-section-to-the-minifo-04b5b6ef`
**Date:** 2026-04-15

## Summary

Implements per-repo policy pack configuration per
`work/policy-pack-configuration.spec.edn`. Repositories can now declare
`:policy-packs` settings in `.miniforge/config.edn` without modifying
the user's global `~/.miniforge/config.edn`. Produced autonomously by
miniforge.

## Changes

### Config layering (`components/config/src/ai/miniforge/config/user.clj`)

Adds a repo-level configuration layer. Precedence is now:

1. **Repo** — `.miniforge/config.edn` (new)
2. **User** — `~/.miniforge/config.edn`
3. **Env** — environment variables
4. **Defaults** — `resources/config/default-user-config.edn`

New functions:

- `repo-config-path` — resolves `.miniforge/config.edn` in the working dir
- `load-repo-config` — loads repo-level config (returns nil if absent)
- `load-merged-config-with-repo` — merges all four layers

### Config interface (`components/config/src/ai/miniforge/config/interface.clj`)

Re-exports the new functions so consumers depend only on the interface.

### Policy pack loading (`components/phase/src/ai/miniforge/phase/agent_behavior.clj`)

- `:policy-packs :extra-search-paths` — additional directories to scan
  for `.pack.edn` files
- `:policy-packs :disabled-pack-ids` — pack IDs to skip across all
  sources (builtins, standards, user, repo, extra)
- Load order: built-in → standards → user (`~/.miniforge/packs/`) →
  repo (`.miniforge/packs/`) → extra search paths

### Defaults (`resources/config/default-user-config.edn`)

Adds an empty `:policy-packs` section with documentation comments
showing the shape.

## Example

```edn
;; .miniforge/config.edn
{:policy-packs
 {:extra-search-paths ["/opt/shared/miniforge-packs"]
  :disabled-pack-ids  [:pack/experimental-feature-x]}}
```

## Test plan

- [x] `user_defaults_test.clj` — config merge precedence
- [x] `agent_behavior_test.clj` — pack loading from all sources, disable-pack filtering
- [x] All 2511 tests pass, 0 new failures

## Files Changed

- `components/config/src/ai/miniforge/config/interface.clj`
- `components/config/src/ai/miniforge/config/user.clj`
- `components/config/test/ai/miniforge/config/user_defaults_test.clj`
- `components/phase/src/ai/miniforge/phase/agent_behavior.clj`
- `components/phase/test/ai/miniforge/phase/agent_behavior_test.clj`
- `resources/config/default-user-config.edn`
