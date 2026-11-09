<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Native Claude and Codex workers on the llm backends

## Overview

Last of three stacked PRs for the issue #2439 agent-worker contract (pilot slice D2): native
Claude and Codex workers that run inside a leased environment through the existing llm backends.
API surface class 1: in-process EDN.

## Layer

Adapters. Depends on the agent-worker lifecycle PR.

## Motivation

Issue #2439 makes native Claude and native Codex the baseline workers. They must use the existing
harness invocation, run inside the selected environment (never on the controller's machine), and
expose real capability differences.

## Changes in Detail

1. Command lines come from `llm/backends` (`:cmd`, `:args-fn` in `cli_args.clj`): prompt on stdin,
   streaming JSON, model and budget from the config. For Claude, a settings file with the tool
   allowances from `config/agent-worker/native.edn` and an MCP config naming no servers are
   written beside the turn, so the harness loads no user or project MCP servers.
2. A turn runs in the background inside the environment; its stream, errors and exit status land
   in files under `<workdir>/.miniforge/worker/<attempt>-<turn>/`. Every argument is single-quoted;
   the prompt never appears on a command line; credential checks print nothing. A launch that
   prints no pid failed. The workdir must be absolute. A cancel that signals nothing reports false.
3. The backend's own stream parser reads the native session id and usage. Claude reports tokens and
   cost; Codex reports tokens only, so its cost stays nil. A Claude turn cut before its result
   frame reports the tokens its messages billed, once per message, with cost nil. Its next turn
   resumes the session the CLI announced in its `system` init frame: the llm Claude parser now
   carries that id as `:announced-session-id`, a new key the llm client and stream callbacks do not
   read, so the default product's resume and tool-call events are unchanged.
4. Capabilities differ: Claude steers between turns (`--resume`) and observes a budget; Codex does
   neither, so a spend grant with a cost ceiling refuses it.
5. Preflight checks the CLI and a credential inside the environment; either missing fails the
   selected worker.

## Testing Plan

A scripted fake environment answers the worker's shell lines: Claude and Codex command lines,
prompt, settings and MCP files, Claude usage and resume from its stream, per-message tokens from a
cut Claude turn and its resume from the init frame, Codex tokens without cost and no steering, missing CLI or
credential, a launch with
no pid, and a relative workdir. Shell quoting, status mapping and credential-name filtering
are unit-tested. The llm parser test checks the init frame's id is announced and is not the
resumable `:session-id`. No real harness runs in CI.

## Deployment Plan

Nothing deploys. Real-harness rows against a cluster come with the Kubernetes provider.

## Related Issues/PRs

Issue #2439. Depends on the agent-worker lifecycle PR.

## Checklist

- [x] Native adapters with scripted-environment tests
- [ ] CI green, Copilot threads resolved, squash-merged
