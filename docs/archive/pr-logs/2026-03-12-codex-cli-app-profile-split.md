<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# CLI App Profile Split

## Layer

Infrastructure

## Depends on

- #306 (`codex/kernel-project-split`) — merged

## What this adds

- moves CLI app identity and filesystem layout into resource-driven profiles
- keeps `miniforge` as the default/base profile
- gives `workflow-kernel` its own CLI name, home directory, and help examples
- makes project-owned CLI profile resources override base defaults explicitly instead of relying on classpath
  enumeration order

## Strata affected

- `bases/cli` — new app profile loader and path helpers
- `projects/workflow-kernel` — kernel-specific CLI identity override
- CLI tests and kernel integration tests — coverage for default and override behavior

## Why

- the shared CLI base was still hardcoding flagship branding and `~/.miniforge` paths even after the runtime and project
  seams were extracted
- the kernel project needs a separate app identity so the governed workflow engine can be renamed independently of the
  flagship software-factory product
- project-level CLI identity should be configuration data owned by the app/project layer, not literals embedded in the
  shared base

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.app-config-test 'ai.miniforge.cli.main-test
  'ai.miniforge.cli.workflow-runner.display-test) ..."`
- `clojure -M -e "(require 'ai.miniforge.workflow.kernel-loader-integration-test) ..."` from `projects/workflow-kernel`
- `bb test`
- `bb test:integration`
- `bb build:kernel`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`
