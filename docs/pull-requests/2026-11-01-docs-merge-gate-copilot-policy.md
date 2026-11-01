<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Tie the merge gate to dewey 723; Copilot findings that conflict with the rules

## Overview

`agents.md`'s PR Merge Gate now names its shared source, the standards rule
`workflows/pr-merge-gate` (dewey 723, miniforge-ai/miniforge-standards#140),
and adds the operator's policy for Copilot findings that conflict with a
standards rule: answer the thread with the trace and the rule, resolve it,
and do not change the code.

## Motivation

On #2317 Copilot asked to restore a function arity that no caller used; the
rules review had removed it under `no-dead-code` (dewey 008). The operator
decided on 2026-11-01 that such threads are answered and resolved by the
session, not left for them. Rule 723 carries that policy for every repository;
this repo's own copy of the gate needs it too.

## Changes in Detail

1. `agents.md`, PR Merge Gate: one paragraph naming rule 723 as the shared
   source, and why the text is repeated here (sessions load `agents.md` through
   `.claude/CLAUDE.md` even when the `standards` submodule is not checked out).
2. `agents.md`, PR Merge Gate: the Copilot-vs-rules bullet, matching rule 723.

## Testing Plan

Documentation only. markdownlint-cli (pinned version, `.markdownlint.jsonc`)
passes on `agents.md`. The rule path and dewey number were checked against
miniforge-standards `main` after #140 merged.

## Deployment Plan

None. Sessions read `agents.md` when they start.

## Related Issues/PRs

- miniforge-ai/miniforge-standards#140 (rule 723)
- #2315 (the gate in `agents.md`), #2317 (the Copilot thread that prompted the policy)
