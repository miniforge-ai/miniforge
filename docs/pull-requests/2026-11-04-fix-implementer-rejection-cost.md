<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: A rejected implementer turn keeps its cost, not only its tokens

## Overview

Part of #2378. The implementer's three rejection responses now carry the
turn's `:cost-usd` alongside its `:tokens`. Those are a parse failure, a
metadata-only MCP submit, and an unverified already-implemented claim on
a repair attempt. An unknown cost stays absent, because the response
builder drops a nil `:cost-usd`.

## Motivation

`process-llm-response` (now in `ai.miniforge.agent.implementer.response`)
built the rejections with `response/error ... {:tokens tokens ...}`, so a
rejected turn's measured cost was lost. The implement phase then fell
back to its flat per-token estimate (`tokens × 0.000015`) for that turn.

## Changes in Detail

1. `implementer/response.clj`: the parse-failed and metadata-only
   `response/error` calls pass `:metrics {:cost-usd cost-usd}`.
   `unverified-already-implemented-response` takes `cost-usd` and does
   the same.

## Testing Plan

New `components/agent/test/ai/miniforge/agent/implementer_rejection_spend_test.clj`
covers the three rejection paths and the nil-cost case. Against main's
`response.clj` it fails 3 of its assertions: each rejection's cost is
nil. With the fix it passes, together with the whole `components/agent`
suite.

## Deployment Plan

No configuration change.

## Related Issues/PRs

- Builds on the `implementer.clj` split (#2391, merged). Before the
  split, the over-budget `implementer.clj` could not be edited.
- The rest of this #2378 fix keeps the implementer's tokens through
  curator fallback and failure in `phase_software_factory/implement.clj`.
  That file is over the SL003 budget and is on hold for #2277.
