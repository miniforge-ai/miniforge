# refactor: Centralize Budget Fallback Resolution

**Branch:** `codex/centralize-budget-fallbacks`
**Base:** `main`
**Date:** 2026-03-10
**Layer:** Application
**Depends On:** #288 merged to `main`

## Overview

Centralizes agent budget fallback resolution so specialized agent constructors
and invocation paths stop open-coding per-role budget defaults.

## Motivation

Agent budget handling is currently spread across multiple namespaces:

- agent implementations independently resolve `:budget :cost-usd`
- review feedback on PR #288 called out these duplicated immediate values and
  inline fallback chains

This makes it too easy for call sites to drift and too hard to audit budget
policy for agent roles. It also hid an inconsistency where the releaser agent
uses role key `:releaser` while the canonical defaults table uses `:release`.

## Changes In Detail

- add one shared agent budget namespace for canonical role-budget lookup and
  runtime budget precedence
- update planner, implementer, tester, and releaser to use the shared resolver
- ensure specialized agent default configs carry their canonical role budget
- map `:releaser` to the canonical `:release` budget defaults
- keep behavior stable while removing duplicated immediate values

## Testing Plan

- [x] Unit coverage for shared budget resolution
- [x] Existing agent tests updated as needed
- [x] `bb scripts/test-changed-bricks.bb`

## Deployment Plan

No special deployment steps.

## Related Issues/PRs

- Follow-up to merged PR #288

## Checklist

- [x] PR doc added
- [x] shared budget resolver added
- [x] callers updated
- [x] tests passing
