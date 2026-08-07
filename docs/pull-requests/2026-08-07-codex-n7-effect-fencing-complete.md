<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: complete N7 effect transaction fencing

## Overview

Marks the effect-transaction fencing specification complete after its durable
identity, create-only persistence, commit claim, authorization, and
reconciliation slices merged.

## Layer

Work queue metadata.

## Depends on

- #1700 — durable authorization and commit claim
- #1701 — durable reconciliation fencing

## Changes

- Move `ariadne-effect-transaction-fencing.spec.edn` to `work/done/`.
- Regenerate `work/QUEUE.md` so runtime grant issuance becomes the active
  Ariadne specification.

## Validation

- `bb work:queue` succeeds and lists grant issuance as ready.
- Markdown formatting and pre-commit gates pass.

## Deployment Plan

Merge to `main`; no runtime behavior changes.
