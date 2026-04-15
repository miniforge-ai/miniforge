<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Compile miniforge-standards.pack.edn from .standards/*.mdc

## Context

The `miniforge-standards.pack.edn` file existed with the correct structure
(pack ID, categories, metadata) but `:pack/rules` was an empty vector. The
MDC compiler (`policy-pack/mdc-compiler.clj`) was fully built (580+ lines)
but had never been run to populate the pack.

This is the first step toward removing the `.standards/` submodule dependency.
Once the knowledge store migrates from direct .mdc file loading to pack-based
rule loading (per `policy-pack-extensibility.spec.edn`), the submodule can
be removed and miniforge consumes only the compiled pack.

## What changed

Ran `mdc-compiler/compile-standards-pack` against `.standards/` to produce
a fully populated pack with 23 rules across 7 categories:

| Category | Rules |
|----------|-------|
| Foundations | code-quality, index, localization, result-handling, simple-made-easy, specification-standards, stratified-design, validation-boundaries |
| Languages | clojure, python, rust, swift |
| Frameworks | kubernetes, polylith |
| Testing | standards |
| Workflows | datever, git-branch-management, git-worktrees, pr-documentation, pr-layering, pre-commit-discipline |
| Project | header-copyright |
| Meta | rule-format |

Each compiled rule includes:

- `:rule/knowledge-content` — full .mdc body text for agent context injection
- `:rule/agent-behavior` — concise directive extracted from the Agent behavior section
- `:rule/applies-to` — phases and file globs from frontmatter
- `:rule/always-inject?` — from `alwaysApply` frontmatter flag
- `:rule/category` — Dewey code from frontmatter

## Verification

- BB can read the compiled pack: `bb -e '(count (:pack/rules (edn/read-string (slurp "..."))))'` → 23
- All `#inst` date literals and namespaced map syntax are BB-compatible
- Existing tests pass (no behavioral change — the pack was already loaded but had no rules)

## Follow-up work

1. Wire `bb standards:pack` task to automate recompilation
2. Wire `bb review` task to run miniforge scan with all policy packs on own repo
3. Complete `policy-pack-extensibility` spec (migrate knowledge store to pack loading)
4. Remove `.standards/` submodule once knowledge store is pack-only
