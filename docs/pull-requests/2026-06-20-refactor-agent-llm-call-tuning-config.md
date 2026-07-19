<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor(agent): externalize curator/meta-eval/heartbeat tuning to config

## Overview

Three LLM-call / timing constants in the `agent` component were hard-coded at
their call sites, escaping the component's existing config-as-data path. This
change moves them into EDN resources, leaving an in-code fallback for the
heartbeat and meta-eval cases. Values are unchanged.

## Motivation

Dewey 007 (config-as-data) asks that tunable LLM-call parameters live in
config, not inline literals. The component already externalizes per-role
LLM-call tuning in `resources/config/agent/agent-llm-defaults.edn`, loaded by
`role_config.clj` and consumed by each `create-{role}` factory. The curator
enrichment call, the meta-evaluator call, and the supervisory-bridge heartbeat
interval were defined inline and never routed through that path.

## Changes

- `resources/config/agent/agent-llm-defaults.edn`: added a `:curator` entry
  `{:temperature 0.1 :max-tokens 800}`. Curator carries the same
  `{:temperature :max-tokens}` shape as the role entries, so it fits the
  existing role-keyed map and its shape invariant.
- `src/ai/miniforge/agent/curator.clj`: `try-llm-chat` now reads its tuning via
  `role-config/agent-llm-default :curator` and merges `{:system ...}` on top,
  replacing the inline `{:temperature 0.1 :max-tokens 800}`.
- `resources/config/agent/meta-eval-tuning.edn` (new): `{:max-tokens 150}`. The
  meta-evaluator call sets no temperature, so it does not fit the role-keyed
  map's "every entry carries :temperature" invariant (enforced by
  `agent-llm-defaults-shape-test`). It gets its own sibling EDN instead.
- `src/ai/miniforge/agent/meta_evaluator.clj`: loads `meta-eval-tuning.edn` at
  ns load into `eval-tuning`, with `fallback-eval-tuning {:max-tokens 150}` as
  the in-code fallback. `evaluate` merges `eval-tuning` into the request map,
  replacing the inline `:max-tokens 150`.
- `resources/config/agent/supervisory-bridge.edn` (new):
  `{:heartbeat-interval-ms 30000}`.
- `src/ai/miniforge/agent/supervisory_bridge.clj`: `default-heartbeat-interval-ms`
  is now derived from `supervisory-bridge.edn` at ns load, with
  `fallback-heartbeat-interval-ms 30000` as the named in-code fallback. The
  downstream reference is unchanged.

All four values are identical to the originals: curator 0.1 / 800, meta-eval
150, heartbeat 30000.

## Verification

- Load smoke from `components/agent`: `agent-llm-default :curator` →
  `{:temperature 0.1 :max-tokens 800}`; `eval-tuning` → `{:max-tokens 150}`;
  `default-heartbeat-interval-ms` → `30000`. All three modified namespaces load.
- Isolated test runner (`-M:test -m cognitect.test-runner -d test`): 515 tests,
  2251 assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.

## Follow-up

None deferred. All three call sites were wired through config.
