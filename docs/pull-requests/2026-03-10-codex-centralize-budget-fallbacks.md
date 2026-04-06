<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Centralize Agent Defaults

**Branch:** `codex/centralize-budget-fallbacks`
**Base:** `main`
**Date:** 2026-03-10
**Layer:** Application
**Depends On:** #288 merged to `main`

## Overview

Centralizes agent default resolution for both budgets and models so specialized
agent constructors stop open-coding per-role budgets and stale model strings.

## Motivation

Agent defaults are currently spread across multiple namespaces:

- agent implementations independently resolve `:budget :cost-usd`
- specialized agents embed Anthropic model ids directly instead of reading from
  shared configuration
- review feedback on PR #288 called out these duplicated immediate values and
  inline fallback chains

This makes it too easy for call sites to drift and too hard to audit agent
policy. It also hid two inconsistencies:

- the releaser agent uses role key `:releaser` while the canonical defaults
  table uses `:release`
- specialized agents were still pinning old Claude model strings instead of
  following configured defaults and the current Claude 4.6 line

## Changes In Detail

- add one shared agent budget namespace for canonical role-budget lookup and
  runtime budget precedence
- update planner, implementer, tester, and releaser to use the shared resolver
- ensure specialized agent default configs carry their canonical role budget
- map `:releaser` to the canonical `:release` budget defaults
- add one shared agent model namespace backed by merged user config
- move specialized agents and reviewer off embedded Claude model literals
- update configured/default Claude models to Sonnet 4.6 and Opus 4.6 where
  appropriate
- move the llm model catalog and task recommendations into an EDN resource so
  model lineup changes are data-only updates instead of code edits
- update model registry and selection recommendations so automatic selection
  matches the configured Claude defaults
- add current OpenAI GPT-5.4 and GPT-5.4 Pro entries from the official OpenAI
  models docs
- refresh stale Gemini registry entries to the current 2.5 lineup used by
  Google’s model docs
- keep behavior stable while removing duplicated immediate values

## Testing Plan

- [x] Unit coverage for shared budget resolution
- [x] Existing agent / llm / phase tests updated as needed
- [x] `bb scripts/test-changed-bricks.bb`

## Deployment Plan

No special deployment steps.

## Related Issues/PRs

- Follow-up to merged PR #288

## Checklist

- [x] PR doc added
- [x] shared budget resolver added
- [x] shared model resolver added
- [x] callers updated
- [x] tests passing
