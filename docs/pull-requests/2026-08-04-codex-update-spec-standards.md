<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: update specification standards

## Overview

Advances the `standards/miniforge` submodule to the reviewed standards release
that governs indexed normative amendments and N7+ product extensions.

## Motivation

Miniforge's current standards pin still forbids all normative specs after N6,
which conflicts with the repository's active N7-N15 contracts and delta specs.
The upstream correction merged in
[miniforge-ai/miniforge-standards#96](https://github.com/miniforge-ai/miniforge-standards/pull/96).

## Changes in Detail

- Update the standards submodule through miniforge-standards merge commit
  [`72f451d`](https://github.com/miniforge-ai/miniforge-standards/commit/72f451d3d7e0ad82079610bec61be0399a2b7117).
- Consume the corrected core/amendment/extension specification rules.

## Test plan

- Verify the gitlink resolves to the merged upstream commit.
- Run the repository's documentation and pre-commit checks.
- Review all intervening standards commits included by the submodule advance.

`bb pre-commit` passes, including 338 smoke tests (1,281 assertions) and 8
GraalVM/Babashka compatibility tests (572 assertions).

## Deployment Plan

Merge to `main`; subsequent N7 spec and implementation PRs consume the updated
standards pin.

## Related Issues/PRs

- [miniforge-ai/miniforge-standards#96](https://github.com/miniforge-ai/miniforge-standards/pull/96)

## Checklist

- [x] Submodule points to merged standards `main`
- [x] Intervening standards changes reviewed
- [x] Repository checks pass
