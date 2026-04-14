<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: use GPT execution backend for dogfooding fallback

## Overview

Switch Miniforge dogfooding and execution defaults away from Claude-first behavior so work can continue when Claude is
rate limited. The PR makes Codex/GPT the default execution backend and enables Codex failover by default.

## Motivation

Dogfooding hit Claude rate limits in the active worktree and the runtime still had multiple Claude-first assumptions:

- shipped config defaulted to Claude
- execution agents reused the shared workflow client even when their model implied another backend
- dogfood tooling assumed Claude availability
- backend failover existed, but default config did not opt into a fallback backend

This left the pipeline unable to recover cleanly when Claude was exhausted.

## Layer

Application / Adapter wiring

## Base Branch

`main`

## Depends On

None.

## Changes in Detail

- Updated LLM client defaults to prefer `:codex` and added client backend introspection.
- Updated agent model policy so execution roles default to `gpt-5.2-codex` and thinking defaults to `gpt-5.4`.
- Wired execution-role agents to resolve a backend-specific client when the shared workflow client is on the wrong
  backend.
- Changed shipped config defaults so `:llm.backend` is `:codex`, execution model defaults to GPT, and self-healing
  allows `[:codex]` failover.
- Moved backend metadata and config fallback defaults into shipped EDN resources instead of hardcoding them in Clojure
  namespaces.
- Updated phase model hints so plan/implement/verify/review align with GPT-backed execution.
- Updated dogfood utilities to use configured backend selection instead of assuming Claude and to accept Codex/OpenAI
  availability in prerequisite checks.
- Added focused tests for role-based backend resolution, default config policy, fallback-resource loading, and
  resource-backed backend defaults.

## Strata Affected

- `ai.miniforge.llm.interface` and `ai.miniforge.llm.protocols.records.llm-client` - default backend policy and backend
  introspection
- `ai.miniforge.agent.model` - role model policy and backend-aware client resolution
- `ai.miniforge.agent.{implementer,tester,reviewer,releaser}` - execution roles now resolve role-specific backend
  clients
- `ai.miniforge.config.user` and CLI config/backends - shipped default backend/failover policy
- `ai.miniforge.meta-runner` and `tasks/dogfood.clj` - dogfood entry points now honor configured GPT/Codex backend

## Testing Plan

- [x] Focused agent/config verification passes in a clean worktree:
  `clojure -M:dev:test -e "(require 'clojure.test 'ai.miniforge.llm.interface 'ai.miniforge.agent.model-test
  'ai.miniforge.config.user-defaults-test 'ai.miniforge.agent.implementer-test 'ai.miniforge.agent.tester-test
  'ai.miniforge.agent.releaser-test) ..."`
- [x] CLI config namespaces load cleanly:
  `clojure -M:dev:test -e "(require 'ai.miniforge.cli.config 'ai.miniforge.cli.backends)"`
- [ ] Full pre-commit / full test suite
  Not run here because broader repo issues already fail outside this change set.

## Deployment Plan

- Merge normally.
- Dogfood runs will pick up Codex/GPT execution by default after deploy.
- Existing users can still override `:llm.backend` and `:llm.model` explicitly.

## Related Issues/PRs

- Triggered by March 28, 2026 dogfood runs hitting Claude rate limits during pipeline execution.

## Risks and Notes

- `components/agent/src/ai/miniforge/agent/reviewer.clj` currently has a pre-existing parse error in `HEAD` unrelated to
  this PR.
- `components/llm/test/ai/miniforge/llm/interface_test.clj` contains a pre-existing wrong-arity test failure unrelated
  to this PR.
- This PR intentionally does not modify unrelated dirty worktree files from the source branch.

## Checklist

- [x] Isolated onto a clean branch from `main`
- [x] Added PR doc under `docs/pull-requests/`
- [x] Kept unrelated worktree changes out of this PR
- [x] Verified focused behavior for GPT execution backend selection
