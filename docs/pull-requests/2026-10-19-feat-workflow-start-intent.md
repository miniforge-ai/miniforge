<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish caller-declared workflow intent

## Purpose and layer

Workflow producer integration for N3: the start publisher forwards the explicit
`:workflow/intent` map from execution options. It does not infer intent from
free-form text, copy arbitrary runtime options, or manufacture an empty map.
Routing correlation and legacy/observer-only behavior remain unchanged.

## Dependencies and scope

Branched from main after #2054; no unmerged dependency. The current constructor
and admission schema already require the map. Host adapters must supply it;
default-host migration and workflow-level refusal propagation remain separate
slices. This is a publisher/journal test, not a full-runner acceptance claim.
No storage migration; normal CLI deployment.

## Verification plan

Exercise the actual publisher and current journal, including durable reopen,
missing/invalid intent, routing correlation and legacy compatibility. Verify
all three workflow consumers and an isolated rebuilt CLI, adversarial standards
review, normal signed hooks/budgets, exact-head review and all CI gates.

## Local verification and adversarial review

- All three workflow consumers: passing, serially (1m33s).
- Rebuilt CLI: 39,185,438 bytes. Isolated package: 38 tests, 156 assertions,
  passing without production source overlays.
- Kondo: no warnings/errors. Both changed namespaces match inferred strata.
- Reviewed explicit field selection, unchanged routing correlation, invalid or
  missing intent refusal, and legacy/nil behavior. Shared journal fixtures verify
  durable reopen. No new validation policy or runtime-option copying is added.
