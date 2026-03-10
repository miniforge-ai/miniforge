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
- update model registry and selection recommendations so automatic selection
  matches the configured Claude defaults
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
