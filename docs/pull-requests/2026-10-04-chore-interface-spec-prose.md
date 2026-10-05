<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: repair interface specification prose lint

## Overview

Repair pre-existing prose violations in N5 and N8 without changing requirements.

## Motivation

The contract reconciliation in #2000 touches both specifications. Keep their
whole-file lint prerequisite separate from the chain and supervisory amendment.

## Layer

Documentation hygiene, based on main at `f2529534`.

## Changes in Detail

Split long sentences and fold single-item lists into prose. Preserve normative
obligations, scope membership, versions, and historical meaning.

## Testing Plan

Whole-file plainspeak and Markdown lint, semantic diff review, normal signed
hooks, clean exact-head Copilot review, and all CI precede merge.

## Deployment Plan

No runtime behavior or contract changes. #2000 carries the separate amendment.

## Related Issues/PRs

Prerequisite for #2000; follows the earlier prose-only repair in #1999.

## Checklist

- [x] Verify lint and semantic equivalence.
- Require normal signed hooks, clean exact-head review, and all CI before merge.
