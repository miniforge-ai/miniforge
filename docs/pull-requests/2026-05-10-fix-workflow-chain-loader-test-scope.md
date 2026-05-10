<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): remove ambient workflow chain and registry assumptions

## Overview

Finish the remaining workflow-side stable-derived cleanup by making the
chain-loader and run7 regression tests use explicit narrowed-scope test
inputs instead of ambient project state.

## Why

`run7_regression_test`, `chain_loader_test`, and `try_load_chain_test`
were still assuming project-wide resources or registry pollution were
present. Narrowed Polylith project runs exposed those hidden
dependencies.

## What changed

- make run7 use the synthetic done phase instead of registry pollution
- make chain-loader tests assert against explicit or stubbed resources
- make try-load-chain scope match the real narrowed resource boundary

## Files changed

- `components/workflow/test/ai/miniforge/workflow/run7_regression_test.clj`
- `components/workflow/test/ai/miniforge/workflow/chain_loader_test.clj`
- `components/workflow/test/ai/miniforge/workflow/anomaly/try_load_chain_test.clj`

## Verification

- focused narrowed-scope workflow tests
- `bb pre-commit`
