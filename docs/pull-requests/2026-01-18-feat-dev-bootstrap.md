# feat: Add Developer Bootstrap with Dependency Installation

**PR**: [#3](https://github.com/miniforge-ai/miniforge/pull/3)
**Branch**: `feat/dev-setup`
**Date**: 2026-01-18
**Status**: Open

## Overview

Add a comprehensive developer bootstrap system that installs all required dependencies via Homebrew and configures the development environment with a single command.

## Motivation

New developers cloning the repo need a fast, reliable way to get a working development environment. Previously, they had to manually install each dependency and configure git hooks. This PR automates that process.

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `.githooks/pre-commit` | Pre-commit hook that validates email pattern and runs `bb pre-commit` |
| `.github/PULL_REQUEST_TEMPLATE.md` | GitHub PR template |
| `docs/pull-requests/` | PR documentation directory |

### Modified Files

| File | Changes |
|------|---------|
| `bb.edn` | Added ~100 lines: `bootstrap`, `setup`, `install:*`, `upgrade:*` tasks |
| `readme.md` | Complete rewrite with bootstrap instructions and task list |

### New Tasks Added

**Installers** (via Homebrew):
- `install:java` - Temurin 21
- `install:clojure` - Clojure CLI
- `install:clj-kondo` - Linter
- `install:markdownlint` - Markdown linter
- `install:poly` - Polylith CLI
- `install:deps` - All of the above

**Upgraders**:
- `upgrade:babashka`, `upgrade:clojure`, `upgrade:clj-kondo`, etc.
- `upgrade:deps` - All of the above

**Setup**:
- `setup:hooks` - Configure git to use `.githooks/`
- `setup:email` - Verify `@miniforge.ai` email
- `bootstrap` - Full bootstrap: install deps + configure env
- `setup` - Alias for bootstrap

## Testing Plan

- [x] `bb bootstrap` runs successfully when deps already installed
- [x] `bb setup` (alias) works
- [x] Git hooks configured correctly via `core.hooksPath`
- [ ] Fresh clone bootstrap works (needs testing on clean machine)

## Deployment Plan

Merge to main. No deployment needed - this is developer tooling.

## Related Issues/PRs

- Follows up on email rewrite to `heimdall@miniforge.ai`
- Implements git hooks for email validation

## Checklist

- [x] Code compiles and runs
- [x] README updated
- [x] Tested locally
- [x] PR created
- [x] PR doc created
