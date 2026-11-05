<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(agent): drop the hook's unreachable LLM meta-eval path

Merged as #2248. This file was added after the merge, with #2251, to meet rule 721.

## Overview

Deletes the PreToolUse hook's LLM meta-evaluator path and moves the hook's Bash deny
regexes from code to `config/agent/tool-policy.edn`. Allow and deny decisions are
unchanged.

## Motivation

`hook-eval` ran the meta-evaluator only when the hook payload carried a `meta_eval`
key. Claude's payload has no such key and no miniforge caller adds one, so the branch,
`evaluate-with-meta`, `trivially-safe-tool?` and `meta_evaluator.clj` never ran. Wiring
it up instead would put an LLM call in front of every tool call.

## Changes in Detail

1. `tool_supervisor.clj`: meta-eval branch and its helpers removed; the namespace fits
   three stratum layers.
2. `meta_evaluator.clj` and `meta-eval-tuning.edn` deleted.
3. `tool-policy.edn` holds the Bash deny patterns, compiled on first use.

## Testing Plan

The existing hook and reply tests pass unchanged.

## Deployment Plan

Shipped with the next release; no behavior change.

## Related Issues/PRs

1. #2251: default-deny tool policy, built on this.
2. Follow-up: remove the meta-eval fields the `tool-use-evaluated` event still accepts.

## Checklist

- [x] Hook tests pass unchanged
- [x] CI green, Copilot review clean
