<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: include missing dev classpath entries

## Overview

Adds the task-executor and web-dashboard components to the dev classpath so
GraalVM/Babashka compatibility tests no longer warn.

## Motivation

Pre-commit GraalVM checks warned that these components were not present on the :dev
classpath. Keeping the dev alias complete avoids noisy warnings and catches dev-only
integration issues sooner.

## Changes in Detail

- Add task-executor and web-dashboard component paths to the :dev alias in deps.edn.

## Testing Plan

- `bb test` (pre-commit) already runs GraalVM compatibility tests.

## Deployment Plan

- No deployment changes.

## Related Issues/PRs

- Addresses GraalVM warnings emitted during pre-commit in PR #149.

## Checklist

- [ ] Confirm GraalVM warnings are gone.
- [ ] Verify :dev alias still resolves.
