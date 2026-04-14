<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# i18n: externalize CLI user-facing strings to message catalog

**Branch:** `i18n/cli-localization-seams`
**Date:** 2026-03-13
**Type:** Localization / i18n

## Summary

Externalizes ~150 hardcoded user-facing strings across 8 CLI source files into the
resource-backed message catalog (`en.edn`), accessed via `(messages/t :key params)`.

This is the first phase of localization seam expansion identified in the open-core
split. All user-facing copy now flows through a single catalog file, enabling future
multi-language support and product-specific message overrides.

## Changes

### Source files (8 files, ~150 strings externalized)

| File | Key prefix | Strings |
|------|-----------|---------|
| `config.clj` | `:config/*` | ~50 — system check messages, LLM/tool/env status, validation |
| `workflow_runner.clj` | `:workflow-runner/*` | ~33 — execution status, phase progress, error messages |
| `commands/pr.clj` | `:pr/*` | ~16 — PR review flow, validation, status updates |
| `observability.clj` | `:observability/*` | ~15 — monitoring status, health checks, alerts |
| `commands/run.clj` | `:run/*` | ~12 — spec execution, input handling, completion |
| `workflow_recommender.clj` | `:recommender/*` | ~8 — recommendation display, scoring |
| `commands/fleet.clj` | `:fleet/*` | ~13 — fleet management, repo operations |
| `workflow_selector.clj` | `:selector/*` | ~11 — workflow selection prompts, descriptions |

### Message catalog

- `bases/cli/resources/config/cli/messages/en.edn` — expanded from ~170 to ~354 lines
  with new keys organized by component namespace

### LLM prompt templates

- `bases/cli/resources/config/workflow/recommendation-prompt-default.edn` — extracted
  LLM prompt templates from `workflow_recommender.clj` into resource file

### Deliberately unchanged

- **Non-CLI components** — TUI, web dashboard, and agent strings are a separate pass
- **Log messages** — structured logging stays in code (not user-facing)
- **Namespaces** — no namespace changes in this PR

## Test plan

- [x] All 1425 unit tests pass (0 failures, 0 errors)
- [x] All modified namespaces compile cleanly
- [ ] `bb build` produces working binary with externalized messages
- [ ] Spot-check: `miniforge doctor` displays catalog-backed system check messages
