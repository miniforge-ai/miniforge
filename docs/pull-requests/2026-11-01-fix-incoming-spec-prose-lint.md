<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: clear inherited specification prose lint

## Overview

Split overlong sentences and fold a single-item list in documentation inherited
from main. Preserve specification obligations, identifiers and authority boundaries.

## Motivation

Normal merge hooks reject the staged incoming documents. This prerequisite keeps
wording-only cleanup separate from the N7 acquisition implementation.

## Changes in Detail

Shorten prose in N14, N15, the specification index and three associated PR docs.
No contract versions, requirement identifiers, implementation or configuration change.

## Testing Plan

Run prose and Markdown lint plus normal signed hooks. Review every edited clause
against its prior wording, preserving normative strength, conditions and scope.

## Deployment Plan

Documentation only; no deployment or migration.

## Related Issues/PRs

Inherited from PRs #2328, #2330 and #2333. Unblocks normal N7 merge validation.

## Checklist

- [ ] Wording-only adversarial and standards review
- [ ] Prose and Markdown lint plus normal signed hooks
- [ ] Fresh full Copilot review and all CI gates
