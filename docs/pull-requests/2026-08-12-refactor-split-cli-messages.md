<!--
  Title: Split cli/messages.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split messages.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.messages` (measured 4 real layers, over the
rule 210 budget of 3) into three namespaces:

- `ai.miniforge.cli.messages.locale` — locale-resolution primitives
  (`default-locale`, `locale-resource`, `lang->locale`).
- `ai.miniforge.cli.messages.rendering` — template-value rendering
  (`render-string`, `render-value`).
- `ai.miniforge.cli.messages` — unchanged public API (`active-locale`,
  `catalog`, `t`), now composing the two sibling namespaces.

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `messages.clj` was 81 lines with two unrelated concerns
(locale resolution and template rendering) stacked into one 4-layer
namespace.

`ai.miniforge.cli.messages` has the largest fan-in of any file
touched so far in this program (40+ requiring namespaces across
`bases/cli` and one project-level test), so the split keeps the
public surface (`active-locale`, `catalog`, `t`) unchanged and in
place — no caller needed a code change. Two previously-private helpers
(`locale-resource`, `lang->locale`) became public (`defn` instead of
`defn-`) since the parent namespace now calls them across a namespace
boundary; their behavior is unchanged.

## Changes in Detail

- New file `messages/locale.clj`: `default-locale`, `locale-resource`,
  `lang->locale` — 1 layer, unchanged behavior.
- New file `messages/rendering.clj`: `render-string`, `render-value`
  — 2 layers, unchanged behavior.
- `messages.clj`: `active-locale`, `catalog`, `t` stay together (3
  layers) — this preserves the existing `messages_test.clj` test,
  which does `(with-redefs [messages/active-locale ...])` and expects
  `catalog`'s internal call to `active-locale` to observe the redef;
  keeping both functions in the same namespace as before means the
  test needed no change.

This is pure code motion: no logic changed, only relocated,
re-namespaced, and (for the two helpers above) made public.

## Testing Plan

- `stratum-lint` clean on all three files (exit 0, was SL003 exit 1
  on the original).
- `clj-kondo` clean on all three files (0 errors, 0 warnings).
- Direct namespace test runs (not just `bb test`, which is unreliable
  under load in this batch):
  - `ai.miniforge.cli.messages-test` — 3 tests, 6 assertions, 0
    failures.
  - 8 sampled `bases/cli` caller test namespaces (`main-test`,
    `workflow-selector-test`, `workflow-runner.display-test`,
    `main.display-test`, and four `main.commands.*-test` files) — 70
    tests, 217 assertions, 0 failures.
  - Project-level `ai.miniforge.workflow.kernel-loader-integration-test`
    (under `projects/miniforge-core`, outside `bb test`'s change-scope)
    — 2 tests, 16 assertions, 0 failures.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program's `bases/cli` batch (see
  `builtin_detectors.clj` split, miniforge#1730, for the established
  convention this follows).

## Checklist

- [x] stratum-lint clean on all three resulting files
- [x] clj-kondo clean on all three resulting files
- [x] Direct namespace tests green (messages, 8 sampled callers,
      1 project-level integration test)
- [x] Adversarial self-review: def set unchanged except two
      necessary defn- → defn visibility flips; public API of
      `ai.miniforge.cli.messages` unchanged
- [x] Fan-in confirmed repo-wide before starting (40+ callers, all
      `:as messages`, none needed a change)
